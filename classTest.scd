MIDIIn.connectAll
(
    f = MIDIdef(\hello, {|...args|
        [\hello, args].postln;
    });
)
f.learn();
f.msgNum = 113;
f.srcID = 123;


Quarks.install("~/dev/LearnGUI/src/Class");

s.boot;
(
    Ndef(\synth, { | amp=0, freq=200 |
        SinOsc.ar([freq, freq*1.1].lag(1)) * amp.lag(2);
    }).play;
)

(
    // CONFIG
    var config = Dictionary.newFrom(List[
        \windowTitle, "MIDI Learn GUI Builder",
        \configFileName, "testConfig.txt", // Make sure to set as we don't want to overwrite other settings files
        \instructions, "MIDI learn GUI components with save functionality. Developed this for Cat's piece and now abstracted it into a class which is pretty useful!",
        \numberOfSynths, 2,
        \backgroundColor, [0.0,0.0,0.0],
        \foregroundColor, [1,1,1],
        \activeColor, [0.9,0.9,0.9],
    ]);

    // HERE IS THE CONSTRUCTION OF THE GUI
    var addControls = {
        var settingsLayout = VLayout(
            StaticText().string_(config[\windowTitle])
                .font_( Font(Font.defaultSerifFace, 36))
                .stringColor_(Color(*config[\foregroundColor])),
            HLayout(
                HLayout(
                    learnGUI.createButton(["Save"], \saveSettings, 90, 35),
                    learnGUI.createButton(["Load"], \loadSettings, 90, 35),
                    nil,
                ),
            ),
            StaticText()
                .string_(config[\instructions])
                .fixedWidth_(300)
                .stringColor_(Color(*config[\foregroundColor]))
                .font_(Font(Font.defaultSerifFace, 18)),
            nil
        );

        // CONTROLS
        var controlsLayout =  CompositeView().layout_(
            VLayout(
                VLayout(
                    StaticText().string_("NUMBER 1").font_(Font(Font.defaultSerifFace, 24))
                        .stringColor_(Color(*config[\foregroundColor])),
                    // learnGUI.createMIDIChooser(\keyboard1),
                ).margins_(10),
                HLayout(
                    learnGUI.createButton(["button 1","ON"], \turnOn),
                    learnGUI.createButton(["button 2","ON"], \turnOn),
                    nil
                ).margins_(10),

                HLayout(
                    learnGUI.createSlider("Volume", \setVol),
                    learnGUI.createSlider("Q", \setFreq),
                    nil
                ).margins_(10),
                nil
            )
        );

        var outputLayout = VLayout(
            HLayout(
                learnGUI.createSlider("Master Volume", \setMasterVolume, 75@200),
                nil,
            ),
                nil,
                nil,
                nil,
            // learnGUI.createButton(["Open EQ"], \toggleEQ, 150),
        );


        s.volume = 0.ampdb;

        w.onClose_({
            MIDIdef.freeAll;
            MIDIClient.disposeClient;
            actions[\quit].();
        });

        // Root layout
        w.layout_(
            HLayout(
                settingsLayout,
                VLayout(
                    HLayout(
                        controlsLayout,
                        outputLayout,
                    ).margins_(10),
                    nil,
                ),
                nil
            ).spacing_(10)
        );
    };

    var actions = ~actions = (
        // CC messages
        \saveSettings: {
            learnGUI.saveSettings();
        },
        \loadSettings: {
            learnGUI.loadSettings();
        },
        \toggleEQ: {
            MasterEQ(2);
        },
        \setVol: {| val |
            Ndef(\synth).set(\amp, val);
        },
        \setFreq: { | val |
            Ndef(\synth).set(\freq, (val.pow(3) * 2000) + 40);
        },
        \turnOn: { | val |
            val.postln;
            switch(val,
                1, { Ndef(\synth).play;},
                0, { Ndef(\synth).stop;},
            );
        },
        \quit: {
        },
        \setMasterVolume: {| sl |
            s.volume = sl.value.ampdb;
        },
    );

    var w = Window.new(config[\windowTitle], 1440@900);
    var learnGUI = ~learnGUI = LearnGUI(config, w, actions);

    w.background = Color(*config[\backgroundColor]);
    addControls.();
    w.front;
)

~learnGUI;
