package dev.codex.sharedhealth.mixin;

import dev.codex.sharedhealth.SharedHealthController;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AgeableMob.class)
public abstract class AgeableMobMixin {
    @Inject(method = "getBabyStartAge", at = @At("RETURN"), cancellable = true)
    private void sharedHealthFabric$scaleAnimalGrowthAge(CallbackInfoReturnable<Integer> callback) {
        if ((Object) this instanceof Animal) {
            callback.setReturnValue(SharedHealthController.INSTANCE.scaleAnimalGrowthAge(callback.getReturnValue()));
        }
    }
}
