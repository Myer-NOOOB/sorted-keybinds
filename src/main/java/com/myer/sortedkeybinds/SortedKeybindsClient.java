package com.myer.sortedkeybinds;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Adds keybinds for Sorted's actions. Everything in Sorted is reached through
 * reflection, so this add-on needs no copy of Sorted to build and does not
 * modify Sorted in any way.
 */
public class SortedKeybindsClient implements ClientModInitializer {
    private static final String MOD_ID = "sorted_keybinds";

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private static final class Binding {
        final KeyMapping key;
        final BooleanSupplier action;
        long lastFire;

        Binding(KeyMapping key, BooleanSupplier action) {
            this.key = key;
            this.action = action;
        }

        void fire() {
            long now = System.nanoTime();
            if (now - lastFire < 250_000_000L) return; // ignore key-repeat
            lastFire = now;
            action.getAsBoolean();
        }
    }

    private static final List<Binding> BINDINGS = new ArrayList<>();

    /** The screen currently open (null while playing), tracked via Fabric screen events. */
    private static Screen currentScreen;

    private static void bind(String name, BooleanSupplier action) {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + "." + name,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, // unbound until you pick a key in Controls
                CATEGORY));
        BINDINGS.add(new Binding(key, action));
    }

    @Override
    public void onInitializeClient() {
        bind("quick_stack_nearby", () -> runSorted("sorted:quick_stack_nearby"));
        bind("smart_empty_nearby", () -> runSorted("sorted:smart_empty_nearby"));
        bind("quick_fill_nearby", () -> runSorted("sorted:quick_fill_nearby"));
        bind("sort_container", SortedKeybindsClient::pressSortContainer);
        bind("clear_highlight", () -> runSorted("sorted:clear_highlight"));
        bind("auto_sort_inventory", () -> runSorted("sorted:auto_sort_inventory"));
        bind("auto_sort_containers", () -> runSorted("sorted:auto_sort_containers"));
        bind("settings", () -> runSorted("sorted:settings"));

        // Keys pressed while playing (no screen open).
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (currentScreen != null) return;
            for (Binding b : BINDINGS) {
                while (b.key.consumeClick()) b.fire();
            }
        });

        // Keys pressed while a chest / inventory screen is open.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            currentScreen = screen;
            ScreenEvents.remove(screen).register(removed -> {
                if (currentScreen == removed) currentScreen = null;
            });
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, keyEvent) -> {
                if (s.getFocused() instanceof EditBox) return true; // typing in a search box
                for (Binding b : BINDINGS) {
                    if (b.key.matches(keyEvent)) {
                        b.fire();
                        return false;
                    }
                }
                return true;
            });
        });
    }

    /** Calls Sorted's public API: SortedActions.run(String id). */
    private static boolean runSorted(String id) {
        try {
            Class<?> actions = Class.forName("com.sorted.api.SortedActions");
            Object result = actions.getMethod("run", String.class).invoke(null, id);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            System.err.println("[sorted_keybinds] could not run " + id + ": " + t);
            return false;
        }
    }

    /** Finds Sorted's "Sort Container" button on the open screen and presses it. */
    private static boolean pressSortContainer() {
        try {
            Screen screen = currentScreen;
            if (!(screen instanceof AbstractContainerScreen<?>)) return false;
            Class<?> widgetClass = Class.forName("com.sorted.client.gui.SortButtonWidget");
            Class<?> pressClass = Class.forName("com.sorted.client.gui.SortButtonWidget$PressAction");
            Method onPress = pressClass.getMethod("onPress", widgetClass, int.class);
            for (Object child : screen.children()) {
                if (!widgetClass.isInstance(child)) continue;
                if (child instanceof AbstractWidget w && (!w.visible || !w.active)) continue;
                Object icon = readField(child, "icon");
                if (icon == null || !icon.toString().contains("sort_container")) continue;
                Object action = readField(child, "action");
                if (action == null) continue;
                onPress.invoke(action, child, 0); // 0 = plain left click
                return true;
            }
        } catch (Throwable t) {
            System.err.println("[sorted_keybinds] could not press Sort Container: " + t);
        }
        return false;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
