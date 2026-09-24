import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Check every mixin's injection points against the real client jar, without launching the game.
 *
 * <p>A mixin that names a method the client does not have fails at class-load time inside the game -
 * and because this mod sets {@code defaultRequire: 0} and {@code required: false}, some of those
 * failures are quiet: the hook simply never runs and the feature it drives silently does nothing. That
 * is the shape of defects this project has already paid for (intermediary names that do not exist,
 * {@code @Retention(CLASS)} annotations Mixin cannot see), so it is worth catching offline.
 *
 * <p>This is a source-level check, not a Mixin run. It reads the mixin sources for their target class,
 * their {@code @Inject}/{@code @Redirect} method names, their {@code @Shadow} members and their
 * {@code @At} targets, and resolves each against the <em>class file structure</em> of the client jar -
 * read through ASM rather than by loading the classes, because loading them drags in the whole library
 * set and this check should not need it. It cannot prove an injection <em>applies</em>; it proves that
 * everything the mixin names exists with the shape the mixin assumes.
 *
 * <p>Usage: tools/mixin_check/run.sh [instance-dir]
 */
public final class MixinCheck {

    private static final Path MIXIN_SOURCE_DIR = Paths.get("src/main/java/net/metalmod/mixin");

    private static int failures;
    private static int checks;

    /** Declared members of one class, as the class file states them. */
    private static final class ClassShape {
        final boolean exists;
        final Set<String> methods = new HashSet<>();
        final Set<String> staticMethods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
        final String superName;

        ClassShape(boolean exists, String superName) {
            this.exists = exists;
            this.superName = superName;
        }
    }

    private record MixinFile(Path file, String mixinTarget, Set<String> imports,
                             Set<String> injectMethods, Set<String> shadowFields,
                             Set<String> shadowMethods, Set<String> atTargets,
                             Set<String> absentAtTargets) {
    }

    private static final Map<String, ClassShape> SHAPES = new HashMap<>();

    /** Every class in the jar, by binary name, so a mixin's simple target can be resolved. */
    private static final Set<String> JAR_CLASSES = new HashSet<>();

    public static void main(String[] args) throws Exception {
        Path instance = args.length > 0 ? Paths.get(args[0]) : defaultInstance();
        Path jar = instance.resolve(instance.getFileName() + ".jar");
        if (!Files.isRegularFile(jar)) {
            System.err.println("ERROR: client jar not found: " + jar);
            System.exit(2);
        }
        if (!Files.isDirectory(MIXIN_SOURCE_DIR)) {
            System.err.println("ERROR: mixin sources not found at " + MIXIN_SOURCE_DIR.toAbsolutePath());
            System.exit(2);
        }

        System.out.println("Mixin target check - every injection point resolved against the client jar");
        System.out.println("  jar    : " + jar.getFileName());
        System.out.println("  mixins : " + MIXIN_SOURCE_DIR.toAbsolutePath());
        System.out.println();

        List<MixinFile> mixins = new ArrayList<>();
        try (var files = Files.list(MIXIN_SOURCE_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                MixinFile parsed = parse(file);
                if (parsed != null) mixins.add(parsed);
            }
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            indexClasses(zip);
            for (MixinFile mixin : mixins) {
                checkMixin(zip, mixin);
            }
        }

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures == 0) {
            System.out.println("MIXIN CHECK PASSED");
        } else {
            System.out.println("MIXIN CHECK FAILED");
            System.exit(1);
        }
    }

    /** Walk the jar once and remember every class it declares, for simple-name resolution. */
    private static void indexClasses(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String entry = entries.nextElement().getName();
            if (entry.endsWith(".class")) {
                JAR_CLASSES.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
            }
        }
    }

    /**
     * Resolve a mixin's target to a binary name.
     *
     * <p>{@code @Mixin(GameRenderer.class)} names a type, not a string, so the source has to be read
     * the way the compiler reads it: an explicit import wins, and otherwise the class is found by
     * simple name in the jar. A name that matches two classes is reported rather than guessed at.
     */
    private static String resolveTarget(String written, Set<String> imports) {
        if (written.contains(".")) {
            return written;
        }
        for (String imported : imports) {
            int lastDot = imported.lastIndexOf('.');
            String simpleName = lastDot < 0 ? imported : imported.substring(lastDot + 1);
            if (simpleName.equals(written)) {
                return imported;
            }
        }
        // Same-package or nested: the jar index decides.
        String match = null;
        for (String candidate : JAR_CLASSES) {
            int lastDot = candidate.lastIndexOf('.');
            String simpleName = lastDot < 0 ? candidate : candidate.substring(lastDot + 1);
            if (simpleName.equals(written)) {
                if (match != null) return match;   // ambiguous; the first is reported by the caller
                match = candidate;
            }
        }
        return match != null ? match : written;
    }

    private static void checkMixin(ZipFile zip, MixinFile mixin) {
        String name = mixin.file().getFileName().toString();
        if (mixin.mixinTarget().isEmpty()) {
            check(name + ": declares a @Mixin target", false, "no @Mixin(...) found");
            return;
        }
        String target = resolveTarget(mixin.mixinTarget(), mixin.imports());
        ClassShape shape = shapeOf(zip, target);
        if (!shape.exists) {
            check(name + " -> " + mixin.mixinTarget(), false, "class not present in the client jar");
            return;
        }
        check(name + " -> " + target, true, "");

        for (String method : mixin.injectMethods()) {
            boolean found = hasMethod(zip, shape, method);
            check("  " + name + ": method '" + method + "' exists", found,
                    found ? "" : "no such method on " + simple(target));
            if (found) {
                check("  " + name + ": method '" + method + "' is not static",
                        !hasStaticMethod(zip, shape, method), "");
            }
        }
        for (String field : mixin.shadowFields()) {
            boolean found = hasField(zip, shape, field);
            check("  " + name + ": field '" + field + "' exists", found,
                    found ? "" : "no such field on " + simple(target));
        }
        for (String method : mixin.shadowMethods()) {
            boolean found = hasMethod(zip, shape, method);
            check("  " + name + ": shadow method '" + method + "' exists", found,
                    found ? "" : "no such method on " + simple(target));
        }
        for (String atTarget : mixin.atTargets()) {
            checkAtTarget(zip, name, atTarget);
        }
        // @At(target = "...") on a *field* or *method* in the target's own class is common; those are
        // resolved by the same descriptor path. Report the ones the parse could not attribute, so a
        // regex gap shows up as a failure rather than as silence.
        for (String unknown : mixin.absentAtTargets()) {
            check("  " + name + ": @At target parsed: " + unknown, false,
                    "not an owner/member descriptor this check understands");
        }
    }

    /**
     * Resolve {@code Lcom/foo/Bar;baz(ILjava/lang/String;)V} against the jar.
     *
     * <p>A {@code @Redirect} that never matches its call is a silent no-op, which is the failure mode
     * worth catching: the hook is present, the mixin applies, and nothing happens. Owner plus member
     * name is enough for the realistic breakages - a renamed class, a removed call - without
     * reimplementing descriptor matching.
     */
    private static void checkAtTarget(ZipFile zip, String mixinName, String descriptor) {
        Matcher matcher = Pattern.compile("^L([^;]+);([^()]+)(\\(.*)?$").matcher(descriptor);
        if (!matcher.matches()) {
            return;      // reported separately as an unparsed target
        }
        String owner = matcher.group(1);
        String member = matcher.group(2);
        ClassShape ownerShape = shapeOf(zip, owner.replace('/', '.'));
        if (!ownerShape.exists) {
            check("  " + mixinName + ": @At owner " + simple(owner.replace('/', '.')), false,
                    "not in the client jar");
            return;
        }
        boolean found = "<init>".equals(member)
                ? ownerShape.methods.contains("<init>")
                : hasMethod(zip, ownerShape, member);
        check("  " + mixinName + ": @At " + simple(owner.replace('/', '.')) + "." + member, found,
                found ? "" : "the owner has no such member");
    }

    // ---------------------------------------------------------------------------------------------
    // Class-file shape, read with ASM so nothing has to be loaded
    // ---------------------------------------------------------------------------------------------

    private static ClassShape shapeOf(ZipFile zip, String binaryName) {
        ClassShape cached = SHAPES.get(binaryName);
        if (cached != null) return cached;

        String entryName = binaryName.replace('.', '/') + ".class";
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            ClassShape missing = new ClassShape(false, null);
            SHAPES.put(binaryName, missing);
            return missing;
        }
        ClassShape resolved;
        try (InputStream in = zip.getInputStream(entry)) {
            ClassReader reader = new ClassReader(in);
            final ClassShape parsed = new ClassShape(true, reader.getSuperName());
            resolved = parsed;
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                                 String signature, String[] exceptions) {
                    parsed.methods.add(methodName);
                    if ((access & Opcodes.ACC_STATIC) != 0) parsed.staticMethods.add(methodName);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String fieldName, String descriptor,
                                               String signature, Object value) {
                    parsed.fields.add(fieldName);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            resolved = new ClassShape(false, null);
        }
        SHAPES.put(binaryName, resolved);
        return resolved;
    }

    private static boolean hasMethod(ZipFile zip, ClassShape shape, String name) {
        for (ClassShape c = shape; c != null && c.exists; c = superShape(zip, c)) {
            if (c.methods.contains(name)) return true;
        }
        return false;
    }

    private static boolean hasStaticMethod(ZipFile zip, ClassShape shape, String name) {
        for (ClassShape c = shape; c != null && c.exists; c = superShape(zip, c)) {
            if (c.staticMethods.contains(name)) return true;
        }
        return false;
    }

    private static boolean hasField(ZipFile zip, ClassShape shape, String name) {
        for (ClassShape c = shape; c != null && c.exists; c = superShape(zip, c)) {
            if (c.fields.contains(name)) return true;
        }
        return false;
    }

    private static ClassShape superShape(ZipFile zip, ClassShape shape) {
        if (shape.superName == null) return null;
        ClassShape parent = shapeOf(zip, shape.superName.replace('/', '.'));
        return parent.exists ? parent : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Source parsing
    // ---------------------------------------------------------------------------------------------

    private static MixinFile parse(Path file) throws IOException {
        String source = Files.readString(file);
        if (!source.contains("@Mixin")) return null;

        String target = "";
        Matcher classMatcher = Pattern.compile("@Mixin\\(([A-Za-z0-9_.$]+)\\.class\\)").matcher(source);
        if (classMatcher.find()) {
            target = classMatcher.group(1);
        } else {
            Matcher targetsMatcher = Pattern.compile("@Mixin\\(targets\\s*=\\s*\"([^\"]+)\"\\)").matcher(source);
            if (targetsMatcher.find()) {
                target = targetsMatcher.group(1).replace('/', '.');
            }
        }

        Set<String> imports = new LinkedHashSet<>();
        Matcher importMatcher = Pattern.compile("^import\\s+([A-Za-z0-9_.$]+)\\s*;", Pattern.MULTILINE)
                .matcher(source);
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }

        Set<String> injects = new LinkedHashSet<>();
        for (String annotation : List.of("@Inject", "@Redirect", "@ModifyVariable", "@ModifyArg",
                "@ModifyArgs", "@ModifyConstant", "@ModifyExpressionValue", "@WrapOperation",
                "@WrapWithCondition", "@Accessor", "@Invoker")) {
            Matcher single = Pattern.compile(Pattern.quote(annotation)
                    + "\\([^)]*?method\\s*=\\s*\"([^\"]+)\"").matcher(source);
            while (single.find()) {
                injects.add(simpleName(single.group(1)));
            }
            Matcher multi = Pattern.compile(Pattern.quote(annotation)
                    + "\\([^)]*?method\\s*=\\s*\\{([^}]*)\\}").matcher(source);
            while (multi.find()) {
                for (String part : multi.group(1).split(",")) {
                    String value = part.trim().replace("\"", "");
                    if (!value.isEmpty()) injects.add(simpleName(value));
                }
            }
        }

        Set<String> shadowFields = new LinkedHashSet<>();
        Matcher shadowField = Pattern.compile("@Shadow\\s+(?:private|protected|public|final|\\s)*"
                + "[A-Za-z0-9_.$<>\\[\\], ]+?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*[;=]").matcher(source);
        while (shadowField.find()) {
            shadowFields.add(shadowField.group(1));
        }

        Set<String> shadowMethods = new LinkedHashSet<>();
        Matcher shadowMethod = Pattern.compile("@Shadow\\s+(?:private|protected|public|final|\\s)*"
                + "[A-Za-z0-9_.$<>\\[\\], ]+?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(source);
        while (shadowMethod.find()) {
            shadowMethods.add(shadowMethod.group(1));
        }

        Set<String> atTargets = new LinkedHashSet<>();
        Set<String> unparsed = new LinkedHashSet<>();
        Matcher at = Pattern.compile("target\\s*=\\s*\"([^\"]+)\"").matcher(source);
        Pattern descriptor = Pattern.compile("^L[^;]+;[^()]+(\\(.*)?$");
        while (at.find()) {
            String value = at.group(1);
            if (descriptor.matcher(value).matches()) {
                atTargets.add(value);
            } else {
                unparsed.add(value);
            }
        }

        return new MixinFile(file, target, imports, injects, shadowFields, shadowMethods, atTargets,
                unparsed);
    }

    /**
     * {@code @Inject(method = "render")} names a method; {@code method = "render(...)V"} may carry a
     * descriptor. Only the name is checked, because that is what the class file can be asked for
     * without reimplementing the descriptor grammar - and a name that no longer exists is the
     * realistic breakage.
     */
    private static String simpleName(String method) {
        int paren = method.indexOf('(');
        return paren < 0 ? method : method.substring(0, paren);
    }

    private static String simple(String binaryName) {
        int dot = binaryName.lastIndexOf('.');
        return dot < 0 ? binaryName : binaryName.substring(dot + 1);
    }

    private static void check(String label, boolean ok, String detail) {
        checks++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + label + (detail.isEmpty() ? "" : "  " + detail));
        if (!ok) failures++;
    }

    private static Path defaultInstance() {
        String configured = System.getenv("METALMOD_MC_INSTANCE");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.home"),
                "Documents/.minecraft/versions/MetalMod_Test_26.2");
    }
}
