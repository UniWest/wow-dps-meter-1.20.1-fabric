package dev.wowdps.mixin;

import dev.wowdps.server.DamageHookContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts DamageTracker#onDamage(DamageSource, float).
 * TargetDummyEntity (MmmMmmMmmMmm) and modded entities call getDamageTracker().onDamage(source, damage)
 * even when they override applyDamage() and do not decrement health.
 */
@Mixin(DamageTracker.class)
public abstract class DamageTrackerMixin {

    @Shadow
    @Final
    private LivingEntity entity;

    @Inject(method = "onDamage", at = @At("HEAD"))
    private void wowdps$onDamageTracked(DamageSource damageSource, float damage, CallbackInfo ci) {
        if (this.entity != null && !this.entity.getWorld().isClient()) {
            DamageHookContext.INSTANCE.onDamageTrackerRecorded(this.entity, damageSource, damage);
        }
    }
}
