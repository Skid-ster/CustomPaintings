package com.jay.custompaintings.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu entrypoint, declared in fabric.mod.json. */
public final class CustomPaintingsModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return CustomPaintingsScreen::new;
    }
}
