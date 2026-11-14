package dev.wowdps.mixin;

import dev.wowdps.server.DamageHookContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Multi-layer damage interceptor on LivingEntity.
 * Crucial for TargetDummyEntity (MmmMmmMmmMmm / dummmmmmy) and custom modded bosses:
 * TargetDummyEntity overrides damage() and applyDamage() without calling super,
 * BUT it calls inherited applyArmorToDamage() and modifyAppliedDamage() on LivingEntity
 * to calculate the exact damage number shown on the dummy!
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @Inject(method = "applyArmorToDamage", at = @At("HEAD"))
    private void wowdps$onApplyArmorHead(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            DamageHookContext.INSTANCE.recordRawIncoming(self, source, amount);
        }
    }

    @Inject(method = "modifyAppliedDamage", at = @At("RETURN"))
    private void wowdps$onModifyAppliedDamageReturn(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            float finalDamage = cir.getReturnValueF();
            DamageHookContext.INSTANCE.onPostMitigationCalculated(self, source, amount, finalDamage);
        }
    }

    @Inject(method = "damage", at = @At("HEAD"))
    private void wowdps$beforeDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            DamageHookContext.INSTANCE.beginOuterDamage(
                self.getId(),
                amount,
                self.getHealth(),
                self.getAbsorptionAmount()
            );
        }
    }

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void wowdps$beforeApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            DamageHookContext.INSTANCE.beginApplyDamage(
                self.getId(),
                amount,
                self.getHealth(),
                self.getAbsorptionAmount()
            );
        }
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void wowdps$afterApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            DamageHookContext.INSTANCE.completeApplyDamage(
                self,
                source,
                amount,
                self.getHealth(),
                self.getAbsorptionAmount()
            );
        }
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void wowdps$afterDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getWorld().isClient()) {
            DamageHookContext.INSTANCE.completeOuterDamage(
                self,
                source,
                amount,
                cir.getReturnValueZ(),
                self.getHealth(),
                self.getAbsorptionAmount()
            );
        }
    }
}
