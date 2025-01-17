package net.earthcomputer.clientcommands.mixin;

import net.earthcomputer.clientcommands.GuiBlocker;
import net.earthcomputer.clientcommands.RenderSettings;
import net.earthcomputer.clientcommands.TempRules;
import net.earthcomputer.clientcommands.task.TaskManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    public void onHandleInputEvents(CallbackInfo ci) {
        TaskManager.tick();
        GuiBlocker.tick();
    }

    @Inject(method = "setWorld", at = @At("HEAD"))
    public void onSetWorld(CallbackInfo ci) {
        TaskManager.onWorldUnload();
    }

    @Inject(method = "openScreen", at = @At("HEAD"), cancellable = true)
    public void onOpenScreen(Screen screen, CallbackInfo ci) {
        if (!GuiBlocker.onOpenGui(screen))
            ci.cancel();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("RETURN"))
    public void onDisconnect(Screen screen, CallbackInfo ci) {
        for (String rule : TempRules.getRules())
            TempRules.reset(rule);
        RenderSettings.clearEntityRenderSelectors();
    }

}
