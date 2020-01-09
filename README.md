# LearnGUI

A GUI class for [SuperCollider](https://supercollider.github.io/) for building MIDI-learnable GUI elements with the ability to save your MIDI mappings to file.

A small dot is placed next to each GUI element that can be clicked on allowing you to then touch the knob/slider/button you want to map and it will be automatically mapped.
Clicking Save will persist those settings in the future. The last saved file will also automatically load on startup if one exists.

### Install
```
Quarks.install("https://github.com/scazan/LearnGUI.git");
```

### Example
```
(
    s.waitForBoot({
        Ndef(\sampleSynth, { | freq=400, amp=0, pan=0 |
            var out;

            out = SinOsc.ar((freq*1000)+60);

            Pan2.ar(out, pan.linlin(0,1,-1,1)) * amp;
        });
    });
)

Ndef(\sampleSynth).playN([0,1]);

// Load the LearnGUI
(
    var config = (
        \windowTitle: "LearnGUI Example",
        \configFileName: "settingsForThisPatch.txt", // Make sure to set as we don't want to overwrite other settings files
        \font: Font.defaultSansFace,
        \fontSize: 16,
        \backgroundColor: [0.0,0.0,0.0],
        \foregroundColor: [1,1,1],
        \activeColor: [0.9,0.9,0.9],
    );

    var actions = (
        \freq: { | val |
            Ndef(\sampleSynth).set(\freq, val);
        },
        \pan: { | val |
            Ndef(\sampleSynth).set(\pan, val);
        },
        \amp: { | val |
            Ndef(\sampleSynth).set(\amp, val);
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
                StaticText().string_("LearnGUI")
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
```
