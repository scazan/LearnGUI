PresetGrid {
    var <>presets;
    var <>buttons;
    var <>numPresets;
    var <>config;
    var <>getValues;
    var <>setValues;
    var <>onChange;
    var <>key;

    *new { |numPresets = 25, config, getValues, setValues, onChange, key = \default|
        ^super.new.init(numPresets, config, getValues, setValues, onChange, key);
    }

    init { |argNumPresets, argConfig, argGetValues, argSetValues, argOnChange, argKey|
        numPresets = argNumPresets;
        config = argConfig ?? Dictionary();
        getValues = argGetValues;
        setValues = argSetValues;
        onChange = argOnChange;
        key = argKey;
        presets = Array.fill(numPresets, { nil });
        buttons = Array.fill(numPresets, { nil });
        ^this;
    }

    createView { |buttonSize = 30, labels|
        var numCols = numPresets.sqrt.ceil.asInteger;
        var numRows = (numPresets / numCols).ceil.asInteger;
        var rows = Array.newClear(numRows);

        numRows.do { |row|
            var buttonsInRow = min(numCols, numPresets - (row * numCols));
            var rowButtons = Array.newClear(buttonsInRow);
            buttonsInRow.do { |col|
                var index = (row * numCols) + col;
                var label = if(labels.notNil && (index < labels.size)) {
                    labels[index].asString
                } {
                    ""
                };
                var button = Button()
                    .fixedHeight_(buttonSize)
                    .minWidth_(buttonSize)
                    .font_(Font(config[\font] ?? Font.defaultMonoFace, (buttonSize * 0.5).max(10).asInteger))
                    .states_([
                        // Empty slot - dimmed appearance
                        [label, Color(*(config[\foregroundColor] ?? [1,1,1])++[0.3]), Color(*(config[\backgroundColor] ?? [0,0,0]))],
                        // Has data - active appearance
                        [label, Color(*(config[\foregroundColor] ?? [1,1,1])), Color(*(config[\activeColor] ?? [0.2, 0.6, 0.2]))]
                    ])
                    .mouseDownAction_({ |view, x, y, modifiers, buttonNumber, clickCount|
                        // Shift key modifier = 131072
                        if(modifiers.bitAnd(131072) > 0) {
                            // Shift-click: clear if has data, store if empty
                            if(presets[index].notNil) {
                                this.clear(index);
                            } {
                                this.store(index);
                            };
                        } {
                            // Regular click: recall values from this slot
                            this.recall(index);
                        };
                        true  // Consume the event, prevent default button toggle
                    });
                // Store button reference
                buttons[index] = button;
                rowButtons[col] = button;
            };
            rows[row] = HLayout(*rowButtons).spacing_(2);
        };

        // Update button states based on any existing preset data
        this.updateUI();

        ^VLayout(*rows).spacing_(2);
    }

    store { |index|
        if(getValues.notNil) {
            presets[index] = getValues.();
            this.updateButtonState(index);
            ("Stored preset " ++ (index + 1)).postln;
            if(onChange.notNil) { onChange.() };
        };
    }

    recall { |index|
        if(presets[index].notNil) {
            if(setValues.notNil) {
                setValues.(presets[index]);
            };
            ("Recalled preset " ++ (index + 1)).postln;
        } {
            ("Preset " ++ (index + 1) ++ " is empty").postln;
        };
    }

    clear { |index|
        presets[index] = nil;
        this.updateButtonState(index);
        ("Cleared preset " ++ (index + 1)).postln;
        if(onChange.notNil) { onChange.() };
    }

    updateButtonState { |index|
        if(buttons[index].notNil) {
            {
                if(presets[index].notNil) {
                    buttons[index].value = 1;
                } {
                    buttons[index].value = 0;
                };
            }.defer;
        };
    }

    updateUI {
        numPresets.do { |i| this.updateButtonState(i) };
    }

    getData {
        ^presets.copy;
    }

    setData { |data|
        if(data.notNil) {
            // Handle size mismatch - preserve existing data, copy what fits
            min(data.size, numPresets).do { |i|
                presets[i] = data[i];
            };
        };
        this.updateUI();
    }

    // Check if any presets are stored
    hasData {
        ^presets.any { |p| p.notNil };
    }

    // Get count of stored presets
    count {
        ^presets.count { |p| p.notNil };
    }

    // Clear all presets
    clearAll {
        numPresets.do { |i| presets[i] = nil };
        this.updateUI();
        if(onChange.notNil) { onChange.() };
    }
}
