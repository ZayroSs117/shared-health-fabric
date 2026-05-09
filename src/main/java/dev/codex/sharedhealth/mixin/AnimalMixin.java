package dev.codex.sharedhealth.mixin;

import dev.codex.sharedhealth.SharedHealthController;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(Animal.class)
public abstract class AnimalMixin {
    @ModifyConstant(method = "finalizeSpawnChildFromBreeding", constant = @Constant(intValue = 6000))
    private int sharedHealthFabric$scaleBreedingCooldown(int vanillaCooldownTicks) {
        return SharedHealthController.INSTANCE.scaleAnimalBreedingCooldown(vanillaCooldownTicks);
    }
}
