(
    s.waitForBoot({
        Ndef(\sampleSynth, { | freq=400, amp=0, pan=0 |
            var out;

            out = SinOsc.ar(freq*1000+60);

            Pan2.ar(out, pan.linlin(0,1,-1,1)) * amp;
        });
    });
)

Ndef(\sampleSynth).playN([0,1]);

// Load the LearnGUI
(
    var config = Dictionary.newFrom(List[
        \windowTitle, "LearnGUI Example",
        \configFileName, "settingsForThisPatch.txt", // Make sure to set as we don't want to overwrite other settings files
        \title, "OTO",
        \font, Font.defaultSansFace,
        \fontSize, 16,
        \backgroundColor, [0.0,0.0,0.0],
        \foregroundColor, [1,1,1],
        \activeColor, [0.9,0.9,0.9],
    ]);

    var actions = (
        \freq: { | val |
            Ndef(\sampleSynth).set(\freq, (val/127));
        },
        \pan: { | val |
            Ndef(\sampleSynth).set(\pan, (val/127));
        },
        \amp: { | val |
            Ndef(\sampleSynth).set(\amp, (val/127));
        },
        \saveSettings: {
            learnGUI.saveSettings();
        },
        \loadSettings: {
            learnGUI.loadSettings();
        },
    );

    var learnGUI = LearnGUI(config, actions);

    learnGUI.setLayout(
        VLayout(
            HLayout(
                StaticText().string_(config[\windowTitle])
                    .font_( Font(config[\font], 36))
                    .stringColor_(Color(*config[\foregroundColor])),
                    nil,
                    nil,
                    nil,
                HLayout(
                    learnGUI.button(["Save"], \saveSettings, 90, 35),
                    learnGUI.button(["Load"], \loadSettings, 90, 35),
                ),
            ),
            HLayout(
                learnGUI.slider("Freq", \freq),
                learnGUI.slider("Pan", \pan),
                learnGUI.slider("Amp", \amp),
            ).spacing_(10),
        )
    );
)

