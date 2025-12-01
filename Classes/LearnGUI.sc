LearnGUI {
    var <>config,
        <>addControls,
        <>actions;

    // Utilities
   var <>guiMappings,
       <>keyboardMappings,
       <>ccMappings,
       <>currentLearningKey,
       <>w;

    var liveInput = true;

    // MIDI Feedback
    var <>midiOutCache;
    var <>feedbackEnabled = true;
    var <>feedbackOverrides;
    var <isProcessingMIDI = false;

    *new { | config, actions |
        ^super.new.init(config, actions);
    }

    init {| config, actions, layout |
        this.config = config;

        this.w = Window.new(config[\windowTitle]);
        this.w.background = Color(*config[\backgroundColor]);
        this.w.onClose_({
            MIDIdef.freeAll;
            MIDIClient.disposeClient;

            if(this.actions[\quit].notNil(), this.actions[\quit]);
        });

        this.w.front;

        this.actions = actions;

        this.guiMappings = Dictionary();
        this.keyboardMappings = Dictionary();
        this.ccMappings = Dictionary();
        this.currentLearningKey = nil;

        // MIDI Feedback initialization
        this.feedbackOverrides = Dictionary();

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
        var mapping, override, ccNum, chan, src, midiOut, midiValue;

        // Check global toggle
        if(feedbackEnabled.not) { ^this };

        // Check per-element override
        override = feedbackOverrides[key];
        if(override.notNil and: { override[\enabled] == false }) { ^this };

        // Prevent feedback loop
        if(isProcessingMIDI) { ^this };

        // Get the learned mapping
        mapping = ccMappings[key];
        if(mapping.isNil) { ^this };

        // Convert 0-1 to 0-127
        midiValue = (value * 127).round.asInteger.clip(0, 127);

        // Determine CC/channel from override or learned mapping
        if(override.notNil and: { override[\cc].notNil }) {
            ccNum = override[\cc];
            chan = override[\chan] ? 0;
            src = override[\src] ? mapping[2];
        } {
            ccNum = mapping[0];
            chan = mapping[1];
            src = mapping[2];
        };

        // Get MIDIOut for this source device
        midiOut = this.getMIDIOutForSource(src);
        if(midiOut.isNil) { ^this };

        // Send CC feedback
        midiOut.control(chan, ccNum, midiValue);
    }

    sendNoteFeedback { |key, value, noteNum, chan, src|
        var midiOut, midiValue, mapping;

        if(feedbackEnabled.not) { ^this };
        if(isProcessingMIDI) { ^this };

        // If no src provided, try to get from ccMappings
        if(src.isNil) {
            mapping = ccMappings[key];
            if(mapping.notNil) { src = mapping[2] };
        };

        if(src.isNil) { ^this };

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

    feedbackToggle { | ...args |
        ^this.createFeedbackToggle(*args);
    }
    createFeedbackToggle {
        ^Button()
            .fixedSize_(120@35)
            .font_(Font(Font.defaultSerifFace, 14))
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
            ).spacing_(0),
            sliderLabel,
        );
    }

    createLearnButton { | key, label, width |
        var learnButton = Button.new(bounds: 0@0)
        .fixedSize_(24@24)
        .font_( Font(this.config[\activeColor], 32))
        .action_({ | button |
            if(button.value == 1, {
                this.currentLearningKey = key;
            }, {
                this.currentLearningKey = nil;
            });
        });
        var learnLabel = label;
        if(label.isNil(), {
            label ="○";
        });
        learnButton.states_([
            [label, Color(*this.config[\activeColor]++[0.2]), Color(*this.config[\backgroundColor]++[0])],
            [label, Color(*this.config[\backgroundColor]), Color(0,0.9,0)]
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
        .font_( Font(Font.defaultSerifFace, 24))
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

        ^HLayout([newButton, \align: \topLeft], [ learnButton, \align: \topLeft ], nil)
            .spacing_(0);
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
        .font_( Font(Font.defaultSerifFace, 12))
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
        .font_(Font(Font.defaultSerifFace, 14));

        // Keep track of this element in a dict
        this.guiMappings.add(key -> [knob, learnButton]);

        ^HLayout(
            VLayout(
            [StaticText()
                .string_("Set the velocity for the alternative tones by striking a key at the desired velocity")
                .maxWidth_(120)
                .font_(Font(Font.defaultSerifFace, 14))
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
        MIDIdef.cc(learningKey, { | val |
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
                MIDIdef.noteOn(learningKey, { | val |
                    isProcessingMIDI = true;
                    {
                        this.guiMappings.at(learningKey.asSymbol)[0].valueAction = val/127;
                        isProcessingMIDI = false;
                    }.defer;
                }, num, chan, src);
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
        // Save feedback settings
        configList.add("feedbackEnabled");
        configList.add(feedbackEnabled.asString);
        configList.add("feedbackOverrides");
        this.feedbackOverrides.keysValuesDo({ |key, override|
            configList.add(key);
            configList.add(override.asString);
        });
        arrayToFile.(configList.asArray, Platform.userConfigDir ++ "/" ++ this.config[\configFileName]);
    }

    loadSettings {
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
                            while({(ccKey = file.getLine()).notNil() and: { ccKey != "feedbackEnabled" }}, {
                                var ccVal = file.getLine();

                                result[\ccMappings][ccKey.asSymbol] = ccVal.interpret;
                            });
                            // Parse feedbackEnabled if we hit it
                            if(ccKey == "feedbackEnabled", {
                                result[\feedbackEnabled] = file.getLine();
                                // Check for feedbackOverrides
                                if(file.getLine() == "feedbackOverrides", {
                                    var overrideKey;
                                    result[\feedbackOverrides] = Dictionary();
                                    while({(overrideKey = file.getLine()).notNil()}, {
                                        var overrideVal = file.getLine();
                                        result[\feedbackOverrides][overrideKey.asSymbol] = overrideVal;
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

        var settingsFilePath = Platform.userConfigDir ++ "/" ++ this.config[\configFileName];
        if(File.exists(settingsFilePath.standardizePath), {
            var configDictionary = fileToDictionary.(settingsFilePath);

            if(configDictionary[\ccMappings].notNil(), {
                configDictionary[\ccMappings].keysValuesDo({ | key, value |
                    this.mapCCListener(key.asSymbol, *value);
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
        }, {
            "No configuration present".postln;
        });

    }

}
