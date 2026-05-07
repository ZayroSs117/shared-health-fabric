package dev.codex.sharedhealth.mixin;

import dev.codex.sharedhealth.SharedHealthController;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobCategory.class)
public abstract class MobCategoryMixin {
    @Inject(method = "getMaxInstancesPerChunk", at = @At("RETURN"), cancellable = true)
    private void sharedHealthFabric$scaleMobCap(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(SharedHealthController.INSTANCE.scaleMobCap(callback.getReturnValue()));
    }
}
