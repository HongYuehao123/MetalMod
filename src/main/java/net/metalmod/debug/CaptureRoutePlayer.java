package net.metalmod.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Replays a {@link CaptureRoute}: normalise the world, then teleport to each waypoint and hold.
 *
 * <p>Teleports run through the integrated server's command dispatcher rather than the client, so a
 * route works in a singleplayer world <em>without cheats</em> - the server console source already has
 * permission. On a real server it falls back to the client command path, which does need operator
 * rights. Output is suppressed so a 60-second capture does not fill chat with one line per waypoint,
 * so a mistyped dimension would fail quietly; that is why the capture summary reports the mean player
 * position per stage. If a stage's position does not match its waypoint, the teleport did not happen.
 *
 * <p>Teleporting is deliberately the mechanism: it is exact, instant and repeatable, unlike walking a
 * path at whatever speed the last run happened to manage. Each stage therefore carries the same
 * arrival cost in every capture, which is what makes two captures comparable stage by stage.
 */
public final class CaptureRoutePlayer {

    /**
     * Commands that make two runs comparable.
     *
     * <p>Time, weather and mob spawning are the things that otherwise differ between two runs of the
     * same route and dwarf the difference being measured - the earlier capture pairs were spoiled
     * partly by exactly that. The dragon and the wither are left alive because killing them changes
     * what the End and a wither fight are; those two scenes are not reproducible and the summary says
     * so.
     */
    private static final List<String> PREP = List.of(
            "gamerule doDaylightCycle false",
            "time set noon",
            "weather clear",
            "gamerule doMobSpawning false",
            "gamerule randomTickSpeed 0",
            "kill @e[type=!player,type=!ender_dragon,type=!wither]",
            "gamemode spectator");

    private final CaptureRoute route;
    private int stage = CaptureRoute.NO_STAGE;
    private long stageEndsAt;
    private boolean finished;

    public CaptureRoutePlayer(CaptureRoute route) {
        this.route = route;
    }

    public CaptureRoute route() {
        return this.route;
    }

    /** Waypoint index for the frames being recorded; {@link CaptureRoute#NO_STAGE} with no route. */
    public int stage() {
        return this.stage;
    }

    public boolean finished() {
        return this.finished;
    }

    /** Run the world-prep commands. Issued on the server thread, in order. */
    public static void prepare(Minecraft minecraft) {
        for (String command : PREP) {
            run(minecraft, command);
        }
    }

    /**
     * Move to the first waypoint before measurement starts, so the opening stage is measured settled
     * rather than measuring the arrival hitch. The dwell clock is deliberately not started here.
     */
    public void arm(Minecraft minecraft) {
        if (this.route.isEmpty()) {
            return;
        }
        teleport(minecraft, 0);
        this.stageEndsAt = 0;
    }

    /** Start the first waypoint's dwell clock at the first measured frame. */
    public void startAt(long now) {
        if (this.stage == CaptureRoute.NO_STAGE) {
            return;
        }
        this.stageEndsAt = now + dwellNanos(this.stage);
    }

    public void tick(Minecraft minecraft, long now) {
        if (this.stage == CaptureRoute.NO_STAGE || this.stageEndsAt == 0 || now < this.stageEndsAt) {
            return;
        }
        int next = this.stage + 1;
        if (next >= this.route.stageCount()) {
            // Hold at the last waypoint for whatever is left of the capture: the tail is a settled
            // steady-state sample, which is the most comparable segment of all.
            this.finished = true;
            this.stageEndsAt = 0;
            return;
        }
        teleport(minecraft, next);
        this.stageEndsAt = now + dwellNanos(next);
    }

    /** One line for the overlay: which waypoint, and whether the route is over. */
    public String status() {
        if (this.stage == CaptureRoute.NO_STAGE) {
            return "preparing route";
        }
        if (this.finished) {
            return "holding at waypoint " + (this.stage + 1) + "/" + this.route.stageCount();
        }
        return "waypoint " + (this.stage + 1) + "/" + this.route.stageCount();
    }

    private long dwellNanos(int index) {
        return Math.max(1, this.route.waypoint(index).dwellMs()) * 1_000_000L;
    }

    private void teleport(Minecraft minecraft, int index) {
        CaptureRoute.Waypoint waypoint = this.route.waypoint(index);
        this.stage = index;
        run(minecraft, String.format(Locale.ROOT, "execute in %s run tp @s %.3f %.3f %.3f %.2f %.2f",
                waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z(),
                waypoint.yaw(), waypoint.pitch()));
    }

    /**
     * Run a command with the player as its source, so {@code @s} resolves.
     *
     * <p>The integrated server is preferred because its console source is already permitted: a
     * singleplayer world needs no cheats. Output is suppressed, so nothing lands in chat mid-capture.
     */
    private static void run(Minecraft minecraft, String command) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            if (minecraft.getConnection() != null) {
                minecraft.getConnection().sendCommand(command);
            }
            return;
        }
        String playerName = minecraft.player == null ? null : minecraft.player.getScoreboardName();
        server.executeIfPossible(() -> {
            ServerPlayer target = playerName == null ? null
                    : server.getPlayerList().getPlayerByName(playerName);
            CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
            if (target != null) {
                source = source.withEntity(target);
            }
            server.getCommands().performPrefixedCommand(source, command);
        });
    }
}
