PresetGrid {
    var <>presets;
    var <>buttons;
    var <>numPresets;
    var <>config;
    var <>getValues;
    var <>setValues;
    var <>onChange;
    var <>key;
    var <>learningIndex;  // Which preset index is currently learning MIDI
    var <>onMidiLearn;    // Callback: { |presetIndex| } when MIDI learned
    var <>learnButton;    // The learn mode toggle button
    var <>midiMappings;   // Dictionary: index -> [num, chan, src, type]

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
        learningIndex = nil;
        midiMappings = Dictionary();
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
                        [label, Color(*(config[\foregroundColor] ?? [1,1,1])), Color(*(config[\activeColor] ?? [0.2, 0.6, 0.2]))],
                        // Learning mode - cyan border/highlight
                        [label, Color.new(0, 0.9, 0.5), Color(*(config[\backgroundColor] ?? [0,0,0]))]
                    ])
                    .mouseDownAction_({ |view, x, y, modifiers, buttonNumber, clickCount|
                        // Check if we're in learn mode
                        if(learnButton.notNil && (learnButton.value == 1)) {
                            // Arm this preset for MIDI learning
                            this.startLearning(index);
                        } {
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
                // Don't update if this button is currently learning
                if(learningIndex != index) {
                    if(presets[index].notNil) {
                        buttons[index].value = 1;
                    } {
                        buttons[index].value = 0;
                    };
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

    // Create a learn button for this preset grid
    createLearnButton { |buttonSize = 18|
        learnButton = Button()
            .fixedSize_(buttonSize@buttonSize)
            .font_(Font(config[\font] ?? Font.defaultMonoFace, 14))
            .focusColor_(Color.clear)
            .states_([
                ["●", Color(*(config[\foregroundColor] ?? [1,1,1])++[0.1]), Color(*(config[\backgroundColor] ?? [0,0,0])++[0])],
                ["●", Color.new(0, 0.9, 0.5), Color(*(config[\backgroundColor] ?? [0,0,0])++[0])]
            ])
            .action_({ |button|
                if(button.value == 0) {
                    // Learning mode disabled, clear any pending learning
                    this.cancelLearning();
                };
            });
        ^learnButton;
    }

    // Start learning MIDI for a specific preset index
    startLearning { |index|
        // Clear any previous learning state
        if(learningIndex.notNil) {
            this.updateButtonState(learningIndex);
        };
        learningIndex = index;
        // Show learning state on the button (state 2 = cyan)
        { buttons[index].value = 2; }.defer;
        ("Preset " ++ (index + 1) ++ " waiting for MIDI...").postln;
        // Notify callback
        if(onMidiLearn.notNil) {
            onMidiLearn.(\start, index);
        };
    }

    // Called when MIDI is successfully learned
    finishLearning { |num, chan, src, type = \noteOn|
        if(learningIndex.notNil) {
            var index = learningIndex;
            midiMappings[index] = [num, chan, src, type];
            ("Preset " ++ (index + 1) ++ " mapped to MIDI " ++ type ++ " " ++ num).postln;
            learningIndex = nil;
            // Reset button state
            this.updateButtonState(index);
            // Turn off learn mode
            { learnButton.value = 0; }.defer;
            // Notify callback
            if(onMidiLearn.notNil) {
                onMidiLearn.(\finish, index, num, chan, src, type);
            };
            if(onChange.notNil) { onChange.() };
        };
    }

    // Cancel learning without mapping
    cancelLearning {
        if(learningIndex.notNil) {
            var index = learningIndex;
            learningIndex = nil;
            this.updateButtonState(index);
        };
    }

    // Check if currently learning
    isLearning {
        ^learningIndex.notNil;
    }

    // Get MIDI mappings data for saving
    getMidiMappings {
        ^midiMappings.copy;
    }

    // Set MIDI mappings data (from loading)
    setMidiMappings { |data|
        if(data.notNil) {
            midiMappings = data.copy;
        };
    }

    // Clear a specific MIDI mapping
    clearMidiMapping { |index|
        midiMappings[index] = nil;
        if(onChange.notNil) { onChange.() };
    }

    // Clear all MIDI mappings
    clearAllMidiMappings {
        midiMappings = Dictionary();
        if(onChange.notNil) { onChange.() };
    }
}
