package net.chimera.gui.options;

import net.chimera.shaderpack.options.OptionEditor;
import net.minecraft.client.gui.Font;

/** What every cell of the settings reads and calls: the values, the words, the overlay and the screen. */
record OptionContext(OptionEditor editor, OptionText text, Font font, OptionOverlay overlay, SliderControl sliders,
                     Actions actions) {
    /** The screen-level actions a cell or header button can start. */
    interface Actions {
        void openPage(String id);

        void back();

        /** Every option to its default, applied at once. */
        void reset();

        void importSettings();

        void exportSettings();
    }
}
