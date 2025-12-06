LearnGUI {
    var <>config,
        <>addControls,
        <>actions;

    // Utilities
   var <>guiMappings,
       <>keyboardMappings,
       <>ccMappings,
       <>noteMappings,
       <>currentLearningKey,
       <>w,
       <>configDir;

    var liveInput = true;

    // MIDI Feedback
    var <>midiOutCache;
    var <>feedbackEnabled = true;
    var <>feedbackOverrides;
    var <isProcessingMIDI = false;

    // Preset Bank
    var <>presetBank;
    var <>presetButtons;

    // Unsaved changes tracking
    var <>isDirty = false;
    var <>onDirtyStateChanged;
    var isLoading = false;

    *new { | config, actions |
        ^super.new.init(config, actions);
    }

    init {| config, actions, layout |
        this.config = config;

        this.w = Window.new(config[\windowTitle]);
        this.w.background = Color(*config[\backgroundColor]);
        this.w.onClose_({
            // Check for unsaved changes before fully closing
            if(isDirty) {
                // Show save dialog centered on screen
                {
                    var screenBounds = Window.screenBounds;
                    var dialogWidth = 300, dialogHeight = 120;
                    var dialogX = (screenBounds.width - dialogWidth) / 2;
                    var dialogY = (screenBounds.height - dialogHeight) / 2;
                    var dialog = Window("Unsaved Changes", Rect(dialogX, dialogY, dialogWidth, dialogHeight), resizable: false);
                    var msg, saveBtn, discardBtn;

                    dialog.background = Color(*this.config[\backgroundColor]);

                    msg = StaticText(dialog, Rect(20, 15, 260, 40))
                        .string_("You had unsaved changes.\nWould you like to save them?")
                        .align_(\center)
                        .font_(Font(this.config[\font], this.config[\fontSize]))
                        .stringColor_(Color(*this.config[\foregroundColor]));

                    saveBtn = Button(dialog, Rect(30, 65, 100, 30))
                        .font_(Font(this.config[\font], this.config[\fontSize]))
                        .states_([["Save", Color(*this.config[\backgroundColor]), Color(*this.config[\activeColor])]])
                        .action_({
                            this.saveSettings();
                            dialog.close();
                        });

                    discardBtn = Button(dialog, Rect(170, 65, 100, 30))
                        .font_(Font(this.config[\font], this.config[\fontSize]))
                        .states_([["Discard", Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])]])
                        .action_({
                            dialog.close();
                        });

                    dialog.front;
                }.defer;
            };

            MIDIdef.freeAll;
            MIDIClient.disposeClient;

            if(this.actions[\quit].notNil(), this.actions[\quit]);
        });

        this.w.front;

        this.actions = actions;

        this.guiMappings = Dictionary();
        this.keyboardMappings = Dictionary();
        this.ccMappings = Dictionary();
        this.noteMappings = Dictionary();
        this.currentLearningKey = nil;
        this.configDir = (thisProcess.nowExecutingPath !? _.dirname) ?? Platform.userConfigDir;

        // Default configFileName based on executing file if not provided
        if(this.config[\configFileName].isNil) {
            var filename = thisProcess.nowExecutingPath !? { |p| p.basename.splitext[0] } ?? "learnGUI";
            this.config[\configFileName] = filename ++ ".midi.config";
        };

        // Default font if not provided
        if(this.config[\font].isNil) {
            this.config[\font] = Font.defaultMonoFace;
        };

        // Default fontSize if not provided
        if(this.config[\fontSize].isNil) {
            this.config[\fontSize] = 16;
        };

        // MIDI Feedback initialization
        this.feedbackOverrides = Dictionary();

        // Preset Bank initialization (25 slots for 5x5 grid)
        this.presetBank = Array.fill(25, { nil });
        this.presetButtons = Array.new;

        // START
        MIDIClient.init(verbose: false);
        MIDIIn.connectAll;
        this.setupMIDIOut();
        this.setupMIDILearn();
        this.loadSettings();
        // this.actions[\start].();

        ^this;
    }

    setLayout { | layout |
        ["setting layout", layout].postln;
        this.w.layout = layout;
    }

    getWindow {
        ^this.w;
    }

    // MIDI Output Setup
    setupMIDIOut {
        midiOutCache = Dictionary();
    }

    getMIDIOutForSource { |src|
        var midiOut, destIndex;

        // Return cached MIDIOut if available
        if(midiOutCache[src].notNil) {
            ^midiOutCache[src];
        };

        // Find destination matching source UID
        // In SuperCollider, input source UIDs often correspond to output destination UIDs
        destIndex = MIDIClient.destinations.detectIndex({ |dest| dest.uid == src });

        if(destIndex.notNil, {
            midiOut = MIDIOut.new(destIndex);
            midiOut.latency = 0;
            midiOutCache[src] = midiOut;
            ^midiOut;
        }, {
            // Fallback: try to find by device name matching
            var srcEndpoint = MIDIClient.sources.detect({ |s| s.uid == src });
            if(srcEndpoint.notNil, {
                destIndex = MIDIClient.destinations.detectIndex({ |dest|
                    dest.device == srcEndpoint.device
                });
                if(destIndex.notNil, {
                    midiOut = MIDIOut.new(destIndex);
                    midiOut.latency = 0;
                    midiOutCache[src] = midiOut;
                    ^midiOut;
                });
            });
        });

        // No matching destination found
        ^nil;
    }

    // MIDI Feedback Methods
    sendFeedback { |key, value|
        var mapping, noteMapping, override, num, chan, src, midiOut, midiValue, isNote = false;

        // Check global toggle
        if(feedbackEnabled.not) { ^this };

        // Check per-element override
        override = feedbackOverrides[key];
        if(override.notNil and: { override[\enabled] == false }) { ^this };

        // Prevent feedback loop
        if(isProcessingMIDI) { ^this };

        // Check for note mapping first, then CC mapping
        noteMapping = noteMappings[key];
        mapping = ccMappings[key];

        if(noteMapping.notNil) {
            isNote = true;
            num = noteMapping[0];
            chan = noteMapping[1];
            src = noteMapping[2];
        } {
            if(mapping.isNil) { ^this };
            num = mapping[0];
            chan = mapping[1];
            src = mapping[2];
        };

        // Apply overrides if present
        if(override.notNil) {
            if(override[\cc].notNil) { num = override[\cc] };
            if(override[\chan].notNil) { chan = override[\chan] };
            if(override[\src].notNil) { src = override[\src] };
        };

        // Convert 0-1 to 0-127
        midiValue = (value * 127).round.asInteger.clip(0, 127);

        // Get MIDIOut for this source device
        midiOut = this.getMIDIOutForSource(src);
        if(midiOut.isNil) { ^this };

        // Send feedback (note or CC based on how it was learned)
        if(isNote) {
            midiOut.noteOn(chan, num, midiValue);
        } {
            midiOut.control(chan, num, midiValue);
        };
    }

    sendNoteFeedback { |key, value, noteNum, chan, src|
        var midiOut, midiValue, mapping;

        if(feedbackEnabled.not) { ^this };
        if(isProcessingMIDI) { ^this };

        // If no params provided, try to get from noteMappings, then ccMappings
        if(noteNum.isNil or: src.isNil) {
            mapping = noteMappings[key];
            if(mapping.notNil) {
                noteNum = noteNum ? mapping[0];
                chan = chan ? mapping[1];
                src = src ? mapping[2];
            } {
                mapping = ccMappings[key];
                if(mapping.notNil) { src = src ? mapping[2] };
            };
        };

        if(src.isNil or: noteNum.isNil) { ^this };

        midiOut = this.getMIDIOutForSource(src);
        if(midiOut.isNil) { ^this };

        midiValue = (value * 127).round.asInteger.clip(0, 127);
        midiOut.noteOn(chan ? 0, noteNum, midiValue);
    }

    setElementFeedback { |key, enabled, ccOverride, chanOverride, srcOverride|
        feedbackOverrides[key] = (
            enabled: enabled,
            cc: ccOverride,
            chan: chanOverride,
            src: srcOverride
        );
    }

    syncAllToHardware {
        guiMappings.keysValuesDo { |key, guiArray|
            var guiElement = guiArray[0];
            if(guiElement.respondsTo(\value)) {
                this.sendFeedback(key, guiElement.value);
            };
        };
    }

    // Mark state as having unsaved changes
    markDirty {
        if(isLoading.not && isDirty.not) {
            isDirty = true;
            if(onDirtyStateChanged.notNil) {
                onDirtyStateChanged.(true);
            };
        };
    }

    // Mark state as saved (called after saveSettings)
    markClean {
        if(isDirty) {
            isDirty = false;
            if(onDirtyStateChanged.notNil) {
                onDirtyStateChanged.(false);
            };
        };
    }

    // Get all current widget values as a Dictionary
    getValues {
        var values = Dictionary();
        guiMappings.keysValuesDo { |key, guiArray|
            var guiElement = guiArray[0];
            if(guiElement.respondsTo(\value)) {
                values[key] = guiElement.value;
            };
        };
        ^values;
    }

    // Set all widget values from a Dictionary (skips buttons to avoid triggering actions)
    setValues { |values|
        values.keysValuesDo { |key, value|
            var guiArray = guiMappings[key.asSymbol];
            if(guiArray.notNil && (guiArray[0].class != Button)) {
                this.setValue(key, value);
            };
        };
    }

    // Get a single widget value by key
    getValue { |key|
        var guiArray = guiMappings[key];
        if(guiArray.notNil and: { guiArray[0].respondsTo(\value) }) {
            ^guiArray[0].value;
        };
        ^nil;
    }

    // Set a single widget value by key (triggers action and feedback)
    setValue { |key, value|
        var guiArray = guiMappings[key.asSymbol];
        if(guiArray.notNil) {
            { guiArray[0].valueAction_(value) }.defer;
        };
    }

    // Set a single widget value without triggering action
    setValueSilent { |key, value|
        var guiArray = guiMappings[key.asSymbol];
        if(guiArray.notNil) {
            { guiArray[0].value_(value) }.defer;
        };
    }

    feedbackToggle { | ...args |
        ^this.createFeedbackToggle(*args);
    }
    createFeedbackToggle {
        ^Button()
            .fixedSize_(120@35)
            .font_(Font(this.config[\font], this.config[\fontSize]))
            .states_([
                ["Feedback OFF", Color(*this.config[\foregroundColor]), Color.gray(0.4)],
                ["Feedback ON", Color(*this.config[\foregroundColor]), Color.green(0.6)]
            ])
            .value_(feedbackEnabled.asInteger)
            .action_({ |but|
                feedbackEnabled = but.value.asBoolean;
            });
    }

    midiChooser { | ...args |
        ^this.createMIDIChooser(*args);
    }
    createMIDIChooser { |key|
        var menu = PopUpMenu(bounds: 200@50)
        .items_(MIDIClient.sources.collect({| source|
            source.name;
        }))
        .action_({ |menu|
            var source = MIDIClient.sources[menu.value];
            MIDIClient.sources[menu.value].name.postln;
            this.keyboardMappings.add(key -> [source.device, source.name]);
        });

        this.guiMappings.add(key -> menu);

        ^menu;
    }

    slider { | ...args |
        ^this.createSlider(*args);
    }
    createSlider { | label, key, dimensions |
        var newSlider = Slider.new()

        .action_({
            this.sendFeedback(key, newSlider.value);
            this.actions[key].(newSlider.value);
        })
        .background_(Color(*this.config[\backgroundColor]))
        .knobColor_(this.config[\knobColor]);

        var learnButton = this.createLearnButton(key);

        var sliderLabel = StaticText.new(bounds: 75@50)
            .font_( Font(this.config[\font], this.config[\fontSize]))
            .string_(label)
            .stringColor_(Color(*this.config[\foregroundColor]));

        if(dimensions.notNil(), {
            newSlider.fixedSize_(dimensions);
        },{
            newSlider.fixedSize_(75@300)
        });
        // Keep track of this element in a dict
        this.guiMappings.add(key -> [newSlider, learnButton]);

        ^VLayout(
            HLayout(
                newSlider,
                [learnButton, \align: \topLeft],
                nil
            ).spacing_(0).margins_(0),
            sliderLabel,
        );
    }

    createLearnButton { | key, label, width |
        var learnButton = Button.new(bounds: 0@0)
        .fixedSize_(18@18)
        .font_( Font(this.config[\font], 14))
        .focusColor_(Color.clear)
        .action_({ | button |
            if(button.value == 1, {
                this.currentLearningKey = key;
            }, {
                this.currentLearningKey = nil;
            });
        });
        var learnLabel = label;
        if(label.isNil(), {
            label ="●";
        });
        learnButton.states_([
            [label, Color(*this.config[\foregroundColor]++[0.1]), Color(*this.config[\backgroundColor]++[0])],
            [label, Color.new(0, 0.9, 0.5), Color(*this.config[\backgroundColor]++[0])]
        ]);
        if(width.notNil(), {
            learnButton.fixedWidth_(width);
        });

        ^learnButton;
    }

    button { | ...args |
        ^this.createButton(*args);
    }
    createButton { | label, key, width, height |
        var newButton = Button.new()
        .fixedSize_(150@100)
        .font_( Font(this.config[\font], this.config[\fontSize]))
        .action_({
            this.sendFeedback(key, newButton.value);
            this.actions[key].(newButton.value);
        });

        var learnButton = this.createLearnButton(key);

        if(label.size == 1, {
            newButton.states_([
                [label[0], Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])],
            ])
        },{
            newButton.states_([
                [label[0], Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])],
                [label[1], Color(*this.config[\foregroundColor]), Color(*this.config[\activeColor])]
            ])
        });
        // Keep track of this element in a dict
        this.guiMappings.add(key -> [newButton, learnButton]);

        if(width.notNil(), {
            newButton.fixedWidth_(width);
        });

        if(height.notNil(), {
            newButton.fixedHeight_(height);
        });

        ^HLayout([newButton, \align: \topLeft], [learnButton, \align: \topLeft], nil)
            .spacing_(0).margins_(0);
    }

    loadSample { | filePath |
        {
            this.config[\sampleFilePath] = filePath;
            ~fileInfo1 = SoundFile.new;
            ~fileInfo1.openRead(filePath);
            ~duration1 = ~fileInfo1.duration;
            ~file1 = Buffer.cueSoundFile(Server.default, filePath, 0, numChannels: ~fileInfo1.numChannels);
            0.1.wait;
            ~file1.normalize;
            ("Loaded " ++ filePath).postln;
        }.fork;
    }

    inputChooser { | ...args |
        ^this.createInputChooser(*args);
    }
    createInputChooser { | labels, action |
        var menu = PopUpMenu(bounds: 180@50)
        .action_(action)
        .items_(labels);

        var loadFile1 = Button(bounds: 180@50)
        .states_([
            ["Load sound file", Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])],
            ["Loaded", Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])]
        ])
        .font_( Font(this.config[\font], this.config[\fontSize]))
        .fixedWidth_(125);

        loadFile1.action = {| button |
            (button.value == 1).if{
                File.openDialog("", this.loadSample, {
                    {
                        ~loadFile11.valueAction=0;
                    }.defer;
                });
            }{ nil;}
        };


        menu.fixedWidth_(125);
        ^VLayout(menu, loadFile1, nil);
    }

    thresholdWidget { | ...args |
        ^this.createThresholdWidget(*args);
    }
    createThresholdWidget { | label, key |
        var knob = Knob()
        .fixedSize_(50@50)
        .action_({
            this.sendFeedback(key, knob.value);
            this.actions[key].(knob.value);
        });

        var learnButton = this.createLearnButton(key, "Set", 75)
        .states_([
            ["Set velocity", Color(*this.config[\foregroundColor]), Color(*this.config[\backgroundColor])],
            [ "Strike a key", Color(*this.config[\foregroundColor]), Color.magenta]
        ])
        .font_(Font(this.config[\font], this.config[\fontSize]));

        // Keep track of this element in a dict
        this.guiMappings.add(key -> [knob, learnButton]);

        ^HLayout(
            VLayout(
            [StaticText()
                .string_("Set the velocity for the alternative tones by striking a key at the desired velocity")
                .maxWidth_(120)
                .font_(Font(this.config[\font], this.config[\fontSize]))
            ,\align: \center],
            nil,
        ),
            VLayout(
                [knob, \align: \center],
                [learnButton, \align: \center],
            ),
            nil
        );
    }

    createPresetBank { |numButtons = 25, buttonSize = 30, labels|
        var numCols = numButtons.sqrt.ceil.asInteger;
        var numRows = (numButtons / numCols).ceil.asInteger;
        var rows = Array.newClear(numRows);

        // Initialize presetButtons array if needed, or resize for different numButtons
        this.presetButtons = Array.fill(numButtons, { nil });

        // Ensure presetBank matches the requested size
        if(this.presetBank.size != numButtons) {
            var oldBank = this.presetBank;
            this.presetBank = Array.fill(numButtons, { nil });
            // Copy over existing presets that fit
            min(oldBank.size, numButtons).do { |i|
                this.presetBank[i] = oldBank[i];
            };
        };

        numRows.do { |row|
            var buttonsInRow = min(numCols, numButtons - (row * numCols));
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
                    .font_(Font(this.config[\font], (buttonSize * 0.5).max(10).asInteger))
                    .states_([
                        // Empty slot - dimmed appearance
                        [label, Color(*this.config[\foregroundColor]++[0.3]), Color(*this.config[\backgroundColor])],
                        // Has data - active appearance
                        [label, Color(*this.config[\foregroundColor]), Color(*this.config[\activeColor])]
                    ])
                    .mouseDownAction_({ |view, x, y, modifiers, buttonNumber, clickCount|
                        // Shift key modifier = 131072
                        if(modifiers.bitAnd(131072) > 0) {
                            // Shift-click: clear if has data, store if empty
                            if(this.presetBank[index].notNil) {
                                this.presetBank[index] = nil;
                                { view.value = 0 }.defer;  // Show as "empty"
                                ("Cleared preset " ++ (index + 1)).postln;
                            } {
                                this.presetBank[index] = this.getValues();
                                { view.value = 1 }.defer;  // Show as "has data"
                                ("Stored preset " ++ (index + 1)).postln;
                            };
                            this.markDirty();
                        } {
                            // Regular click: recall values from this slot
                            if(this.presetBank[index].notNil) {
                                this.setValues(this.presetBank[index]);
                                ("Recalled preset " ++ (index + 1)).postln;
                            } {
                                ("Preset " ++ (index + 1) ++ " is empty").postln;
                            };
                        };
                        true  // Consume the event, prevent default button toggle
                    });
                // Store button reference for later UI updates
                this.presetButtons[index] = button;
                rowButtons[col] = button;
            };
            rows[row] = HLayout(*rowButtons).spacing_(2);
        };

        // Update button states based on loaded presets
        this.updatePresetBankUI();

        ^VLayout(*rows).spacing_(2);
    }

    // Update preset button visual states based on presetBank data
    updatePresetBankUI {
        this.presetButtons.do { |button, index|
            if(button.notNil) {
                {
                    if(this.presetBank[index].notNil) {
                        button.value = 1;  // Show as "has data"
                    } {
                        button.value = 0;  // Show as "empty"
                    };
                }.defer;
            };
        };
    }

    mapKeyboardListener { | learningKey, device, name |
        var indexOfKeyboard = 0;
        var midiMenu = this.guiMappings[learningKey];
        midiMenu.items.do({ |deviceName, i|
            this.keyboardMappings.postln;
            if(deviceName == name, {
                indexOfKeyboard = i;
            });
        });

        midiMenu.value = indexOfKeyboard;
    }

    mapCCListener { | learningKey, num, chan, src |
        this.ccMappings[learningKey] = [num, chan, src];
        this.markDirty();
        MIDIdef.cc(learningKey, { | val |
            isProcessingMIDI = true;
            {
                this.guiMappings.at(learningKey.asSymbol)[0].valueAction = val/127;
                isProcessingMIDI = false;
            }.defer;
        }, num, chan, src);
    }

    mapNoteListener { | learningKey, num, chan, src |
        this.noteMappings[learningKey] = [num, chan, src];
        this.markDirty();
        MIDIdef.noteOn(learningKey, { | val |
            isProcessingMIDI = true;
            {
                this.guiMappings.at(learningKey.asSymbol)[0].valueAction = val/127;
                isProcessingMIDI = false;
            }.defer;
        }, num, chan, src);
    }

    setupMIDILearn {
        MIDIdef.freeAll;
        MIDIdef.cc(\ccLearn, { | val, num, chan, src |
            if(this.currentLearningKey.notNil() && (this.currentLearningKey != \thresh), {
                var learningKey = this.currentLearningKey.asString();
                {this.guiMappings.at(learningKey.asSymbol)[1].valueAction = 0;}.defer;
                this.currentLearningKey = nil;
                this.mapCCListener(learningKey, num, chan, src);
            });
        });

        MIDIdef.noteOn(\noteOnLearn, { | value, num, chan, src |
            if(this.currentLearningKey.notNil() && (this.currentLearningKey != \thresh), {
                var learningKey = this.currentLearningKey.asString();
                {this.guiMappings.at(learningKey.asSymbol)[1].valueAction = 0;}.defer;
                this.currentLearningKey = nil;
                this.mapNoteListener(learningKey, num, chan, src);
            });
        });
        MIDIdef.noteOn(\threshLearn, { | velocity, num, chan, src |
            if(this.currentLearningKey == \thresh, {
                var learningKey = this.currentLearningKey.asString();

                this.currentLearningKey = nil;

                {
                    this.guiMappings.at(learningKey.asSymbol)[1].valueAction = 0;
                }.defer;

                isProcessingMIDI = true;
                {
                    this.guiMappings.at(learningKey.asSymbol)[0].valueAction = velocity/127;
                    isProcessingMIDI = false;
                }.defer;
            });
        });
    }

    saveSettings {
        var arrayToFile = {| array, path |
            var file;
            file = File.new(path.standardizePath, "w");
            array.size.do{|i| file.write(array[i].value.asString ++ "\n"); };
            file.close;
            this.markClean();
            "SAVED".postln;
        };
        var configList = List.newUsing(this.config.getPairs);

        configList.add("keyboardMappings");
        this.keyboardMappings.keysValuesDo({ |key, map|
            configList.add(key);
            configList.add(map[0]);
            configList.add(map[1]);
        });
        configList.add("ccMappings");
        this.ccMappings.getPairs.do({ |map|
            configList.add(map);
        });
        configList.add("noteMappings");
        this.noteMappings.getPairs.do({ |map|
            configList.add(map);
        });
        // Save feedback settings
        configList.add("feedbackEnabled");
        configList.add(feedbackEnabled.asString);
        configList.add("feedbackOverrides");
        this.feedbackOverrides.keysValuesDo({ |key, override|
            configList.add(key);
            configList.add(override.asString);
        });
        // Save presets
        configList.add("presetBank");
        configList.add(this.presetBank.size.asString);
        this.presetBank.do({ |preset, index|
            if(preset.notNil) {
                configList.add(index.asString);
                configList.add(preset.asCompileString);
            };
        });
        configList.add("endPresetBank");
        ("Saving settings to: " ++ configDir ++ "/" ++ this.config[\configFileName]).postln;
        arrayToFile.(configList.asArray, configDir ++ "/" ++ this.config[\configFileName]);
    }

    loadSettings {
        var settingsFilePath = configDir ++ "/" ++ this.config[\configFileName];
        var fileToDictionary = {| path |
            var file, fileValues, t, array, lineNumber=0;
            var result = Dictionary();
            file = File.new(path.standardizePath, "r");

            while({ (t = file.getLine).notNil},{
                var key, value;
                key = t;
                // At the end of the file, we specify keyboard and cc mappings
                if(key == "keyboardMappings", {
                    var keyboardKey;
                    result[\keyboardMappings] = Dictionary();
                    while({(keyboardKey = file.getLine()).notNil()}, {
                        var keyboardDevice, keyboardDeviceName;

                        if(keyboardKey == "ccMappings", {
                            var ccKey;
                            result[\ccMappings] = Dictionary();
                            while({(ccKey = file.getLine()).notNil() and: { ccKey != "noteMappings" }}, {
                                var ccVal = file.getLine();

                                result[\ccMappings][ccKey.asSymbol] = ccVal.interpret;
                            });
                            // Parse noteMappings if we hit it
                            if(ccKey == "noteMappings", {
                                var noteKey;
                                result[\noteMappings] = Dictionary();
                                while({(noteKey = file.getLine()).notNil() and: { noteKey != "feedbackEnabled" }}, {
                                    var noteVal = file.getLine();
                                    result[\noteMappings][noteKey.asSymbol] = noteVal.interpret;
                                });
                                // Parse feedbackEnabled if we hit it
                                if(noteKey == "feedbackEnabled", {
                                    result[\feedbackEnabled] = file.getLine();
                                    // Check for feedbackOverrides
                                    if(file.getLine() == "feedbackOverrides", {
                                        var overrideKey;
                                        result[\feedbackOverrides] = Dictionary();
                                        while({(overrideKey = file.getLine()).notNil() and: { overrideKey != "presetBank" }}, {
                                            var overrideVal = file.getLine();
                                            result[\feedbackOverrides][overrideKey.asSymbol] = overrideVal;
                                        });
                                        // Check for presetBank
                                        if(overrideKey == "presetBank", {
                                            var presetSize = file.getLine().asInteger;
                                            var presetIndex;
                                            result[\presetBank] = Array.fill(presetSize, { nil });
                                            while({(presetIndex = file.getLine()).notNil() and: { presetIndex != "endPresetBank" }}, {
                                                var presetData = file.getLine();
                                                result[\presetBank][presetIndex.asInteger] = presetData.interpret;
                                            });
                                        });
                                    });
                                });
                            });
                        }, {
                            keyboardDevice = file.getLine();
                            keyboardDeviceName = file.getLine();

                            result[\keyboardMappings][keyboardKey.asSymbol] = [keyboardDevice, keyboardDeviceName];
                        });
                    });

                }, {
                    value = file.getLine();
                    result[key.asSymbol] = value;
                });
            });

            file.close;

            result;
        };
        if(File.exists(settingsFilePath.standardizePath), {
            var configDictionary = fileToDictionary.(settingsFilePath);

            // Suppress dirty marking during load
            isLoading = true;

            if(configDictionary[\ccMappings].notNil(), {
                configDictionary[\ccMappings].keysValuesDo({ | key, value |
                    this.mapCCListener(key.asSymbol, *value);
                });
            });

            if(configDictionary[\noteMappings].notNil(), {
                configDictionary[\noteMappings].keysValuesDo({ | key, value |
                    this.mapNoteListener(key.asSymbol, *value);
                });
            });

            if(configDictionary[\keyboardMappings].notNil(), {
                this.keyboardMappings = configDictionary[\keyboardMappings];
                configDictionary[\keyboardMappings].keysValuesDo({ | key, value |
                    this.mapKeyboardListener(key.asSymbol, *value);
                });
            });

            if(configDictionary[\sampleFilePath].notNil(), {
                this.loadSample(configDictionary[\sampleFilePath]);
            });

            // Load feedback settings
            if(configDictionary[\feedbackEnabled].notNil(), {
                feedbackEnabled = configDictionary[\feedbackEnabled].interpret;
            });

            if(configDictionary[\feedbackOverrides].notNil(), {
                configDictionary[\feedbackOverrides].keysValuesDo({ |key, value|
                    feedbackOverrides[key.asSymbol] = value.interpret;
                });
            });

            // Load presets
            if(configDictionary[\presetBank].notNil(), {
                this.presetBank = configDictionary[\presetBank];
            });

            // Done loading
            isLoading = false;
        }, {
            "No configuration present".postln;
        });

    }

}
