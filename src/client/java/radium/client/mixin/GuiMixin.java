package radium.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import radium.client.SpoofConfig;

/**
 * Applies a client-side number-format override to the local player's score in
 * the vanilla sidebar. Using a FixedFormat lets Radium display values larger
 * than Minecraft's normal integer-backed scoreboard range without changing the
 * real server score.
 */
@Mixin(Gui.class)
public final class GuiMixin {
    private static Objective radium$lastSpoofedObjective;

    @ModifyVariable(
            method = "extractScoreboardSidebar",
            at = @At("STORE"),
            ordinal = 0,
            require = 0
    )
    private Objective radium$spoofSidebarScore(Objective objective) {
        Minecraft client = Minecraft.getInstance();

        if (objective == null) {
            SpoofConfig.lastSidebarObjective = "";
            SpoofConfig.statusMessage = SpoofConfig.enabled ? "No sidebar objective detected" : "Spoofing disabled";
            radium$clearLastOverride(client);
            return null;
        }

        String currentObjectiveName = objective.getName();
        SpoofConfig.lastSidebarObjective = currentObjectiveName;

        if (client.player == null || client.level == null) {
            SpoofConfig.statusMessage = "Waiting for a world/player";
            return objective;
        }

        if (!SpoofConfig.enabled) {
            SpoofConfig.statusMessage = "Spoofing disabled";
            radium$clearLastOverride(client);
            return objective;
        }

        String targetObjective = SpoofConfig.targetObjective == null
                ? ""
                : SpoofConfig.targetObjective.trim();

        if (!targetObjective.isBlank() && !targetObjective.equals(currentObjectiveName)) {
            SpoofConfig.statusMessage = "Waiting for objective: " + targetObjective;
            radium$clearLastOverride(client);
            return objective;
        }

        String displayValue = SpoofConfig.fakeValue == null
                ? ""
                : SpoofConfig.fakeValue.trim();

        if (displayValue.isBlank()) {
            SpoofConfig.statusMessage = "Enter a fake display value";
            radium$clearLastOverride(client);
            return objective;
        }

        try {
            Scoreboard scoreboard = client.level.getScoreboard();
            ScoreHolder holder = client.player;
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(holder, objective);

            if (radium$lastSpoofedObjective != null && radium$lastSpoofedObjective != objective) {
                radium$clearLastOverride(client);
            }

            score.numberFormatOverride(new FixedFormat(Component.literal(displayValue)));
            radium$lastSpoofedObjective = objective;
            SpoofConfig.statusMessage = "Spoofing " + currentObjectiveName + " as " + displayValue;
        } catch (RuntimeException ignored) {
            SpoofConfig.statusMessage = "This scoreboard rejected the client-side override";
        }

        return objective;
    }

    private static void radium$clearLastOverride(Minecraft client) {
        if (radium$lastSpoofedObjective == null || client.player == null || client.level == null) {
            radium$lastSpoofedObjective = null;
            return;
        }

        try {
            Scoreboard scoreboard = client.level.getScoreboard();
            ScoreAccess score = scoreboard.getOrCreatePlayerScore(client.player, radium$lastSpoofedObjective);
            score.numberFormatOverride(null);
        } catch (RuntimeException ignored) {
            // If the scoreboard disappears while changing worlds, there is nothing useful to restore.
        } finally {
            radium$lastSpoofedObjective = null;
        }
    }
}
