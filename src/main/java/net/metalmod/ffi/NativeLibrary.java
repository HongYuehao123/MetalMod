package net.metalmod.ffi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** One native image shared by the renderer and memory Panama bindings. */
public final class NativeLibrary {
    private static boolean loaded;

    private NativeLibrary() {}

    /** Extract and load once; independent extractions register duplicate Objective-C classes. */
    public static synchronized void load() throws IOException {
        if (loaded) return;
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac"))
            throw new UnsupportedOperationException("MetalMod requires macOS.");
        Path library = Path.of("native/build/libmetalmod.dylib");
        if (!Files.isRegularFile(library)) {
            InputStream resource = NativeLibrary.class.getResourceAsStream("/natives/libmetalmod.dylib");
            if (resource == null) resource = NativeLibrary.class.getResourceAsStream("/libmetalmod.dylib");
            if (resource == null) throw new IOException("Embedded libmetalmod.dylib not found");
            try (InputStream source = resource) {
                library = Files.createTempFile("libmetalmod-", ".dylib");
                library.toFile().deleteOnExit();
                Files.copy(source, library, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.load(library.toAbsolutePath().toString());
        loaded = true;
    }
}
