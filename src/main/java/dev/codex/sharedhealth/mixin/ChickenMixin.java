package dev.codex.sharedhealth.mixin;

import dev.codex.sharedhealth.SharedHealthController;
import net.minecraft.world.entity.animal.chicken.Chicken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chicken.class)
public abstract class ChickenMixin {
    @Shadow
    public int eggTime;

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 6000))
    private int sharedHealthFabric$scaleInitialEggLayTime(int vanillaTicks) {
        return SharedHealthController.INSTANCE.scaleChickenEggLayTime(vanillaTicks);
    }

    @ModifyConstant(method = "aiStep", constant = @Constant(intValue = 6000))
    private int sharedHealthFabric$scaleNextEggLayTime(int vanillaTicks) {
        return SharedHealthController.INSTANCE.scaleChickenEggLayTime(vanillaTicks);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void sharedHealthFabric$capExistingEggLayTime(CallbackInfo callback) {
        eggTime = SharedHealthController.INSTANCE.capChickenEggLayTime(eggTime);
    }
}
