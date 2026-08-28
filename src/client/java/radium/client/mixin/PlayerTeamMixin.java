package radium.client.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import radium.client.DynamicSpoofer;
import radium.client.LineSpoofer;

/**
 * Intercepts incoming client-side scoreboard-team prefix writes.
 *
 * <p>Radium captures the untouched server component and substitutes the local
 * display text before the refreshed prefix enters the live client scoreboard.
 * This prevents one-frame flashes while preserving the raw value for Dynamic
 * tracking.</p>
 */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {
    @ModifyVariable(
            method = "setPlayerPrefix",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0,
            remap = false
    )
    private Component radium$interceptPlayerPrefixWrite(Component incomingPrefix) {
        Component fixed = LineSpoofer.interceptPrefixWrite(this, incomingPrefix);
        return DynamicSpoofer.interceptPrefixWrite(this, fixed);
    }

    /** Compatibility alias for mapping/API variations. */
    @ModifyVariable(
            method = "setPrefix",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0,
            remap = false
    )
    private Component radium$interceptPrefixWriteAlias(Component incomingPrefix) {
        Component fixed = LineSpoofer.interceptPrefixWrite(this, incomingPrefix);
        return DynamicSpoofer.interceptPrefixWrite(this, fixed);
    }
}
