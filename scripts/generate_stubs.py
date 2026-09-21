#!/usr/bin/env python3
import os
import sys

STUBS = {
    "net/fabricmc/api/ClientModInitializer.java": """package net.fabricmc.api;
public interface ClientModInitializer {
    void onInitializeClient();
}
""",
    "com/terraformersmc/modmenu/api/ConfigScreenFactory.java": """package com.terraformersmc.modmenu.api;
import net.minecraft.client.gui.screens.Screen;
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
""",
    "com/terraformersmc/modmenu/api/ModMenuApi.java": """package com.terraformersmc.modmenu.api;
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return null;
    }
}
""",
    "net/minecraft/network/chat/Component.java": """package net.minecraft.network.chat;
public interface Component {
    static MutableComponent literal(String text) {
        return null;
    }
}
""",
    "net/minecraft/network/chat/MutableComponent.java": """package net.minecraft.network.chat;
public interface MutableComponent extends Component {
}
""",
    "com/mojang/blaze3d/pipeline/RenderTarget.java": """package com.mojang.blaze3d.pipeline;
public abstract class RenderTarget {
    public int width;
    public int height;
    public int viewWidth;
    public int viewHeight;
    public void resize(int width, int height, boolean clearError) {}
    public void blitToScreen(int width, int height) {}
    public int getColorTextureId() { return 0; }
    public int getDepthTextureId() { return 0; }
}
""",
    "com/mojang/blaze3d/platform/Window.java": """package com.mojang.blaze3d.platform;
public final class Window {
    public int getWidth() { return 1920; }
    public int getHeight() { return 1080; }
    public int getFramebufferWidth() { return 1920; }
    public int getFramebufferHeight() { return 1080; }
}
""",
    "net/minecraft/client/Minecraft.java": """package net.minecraft.client;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
public class Minecraft {
    public RenderTarget mainRenderTarget;
    public Window window;
    private static Minecraft instance;
    public static Minecraft getInstance() { return instance; }
    public void setScreenAndShow(Screen screen) {}
    public RenderTarget getMainRenderTarget() { return mainRenderTarget; }
    public Window getWindow() { return window; }
    public void resizeDisplay() {}
}
""",
    "net/minecraft/client/gui/GuiGraphicsExtractor.java": """package net.minecraft.client.gui;
public interface GuiGraphicsExtractor {}
""",
    "net/minecraft/client/gui/components/events/GuiEventListener.java": """package net.minecraft.client.gui.components.events;
public interface GuiEventListener {}
""",
    "net/minecraft/client/gui/components/Renderable.java": """package net.minecraft.client.gui.components;
public interface Renderable {}
""",
    "net/minecraft/client/gui/narration/NarratableEntry.java": """package net.minecraft.client.gui.narration;
public interface NarratableEntry {}
""",
    "net/minecraft/client/gui/components/Button.java": """package net.minecraft.client.gui.components;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
public class Button implements GuiEventListener, Renderable, NarratableEntry {
    @FunctionalInterface
    public interface OnPress {
        void onPress(Button button);
    }
    public static class Builder {
        public Builder(Component message, OnPress onPress) {}
        public Builder bounds(int x, int y, int width, int height) { return this; }
        public Button build() { return new Button(); }
    }
    public static Builder builder(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }
    public void setMessage(Component message) {}
}
""",
    "net/minecraft/client/gui/screens/Screen.java": """package net.minecraft.client.gui.screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
public abstract class Screen {
    public int width;
    public int height;
    public Minecraft minecraft;
    protected Screen(Component title) {}
    protected void init() {}
    public void onClose() {}
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) { return widget; }
}
""",
    "org/joml/Matrix4f.java": """package org.joml;
public class Matrix4f {
    public float m20() { return 0f; }
    public float m21() { return 0f; }
    public Matrix4f m20(float v) { return this; }
    public Matrix4f m21(float v) { return this; }
}
""",
    "org/spongepowered/asm/mixin/Mixin.java": """package org.spongepowered.asm.mixin;
import java.lang.annotation.*;
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Mixin {
    Class<?>[] value() default {};
    String[] targets() default {};
}
""",
    "org/spongepowered/asm/mixin/Shadow.java": """package org.spongepowered.asm.mixin;
import java.lang.annotation.*;
@Target({ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.CLASS)
public @interface Shadow {}
""",
    "org/spongepowered/asm/mixin/injection/Inject.java": """package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface Inject {
    String[] method();
    At at();
    boolean cancellable() default false;
    int require() default -1;
}
""",
    "org/spongepowered/asm/mixin/injection/ModifyVariable.java": """package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ModifyVariable {
    String[] method();
    At at();
    int ordinal() default -1;
    String name() default "";
    boolean argsOnly() default false;
    int require() default -1;
}
""",
    "org/spongepowered/asm/mixin/injection/At.java": """package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface At {
    String value();
}
""",
    "org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java": """package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfo {
    public void cancel() {}
}
""",
    "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.java": """package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfoReturnable<R> extends CallbackInfo {
    public R getReturnValue() { return null; }
    public void setReturnValue(R returnValue) {}
}
"""
}

def main():
    target_dir = sys.argv[1] if len(sys.argv) > 1 else "build/stubs"
    for rel_path, content in STUBS.items():
        full_path = os.path.join(target_dir, rel_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content)
    print(f"Generated {len(STUBS)} compile stubs in {target_dir}")

if __name__ == "__main__":
    main()
