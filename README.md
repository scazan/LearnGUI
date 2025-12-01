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

### MIDI Feedback

LearnGUI supports bidirectional MIDI communication, allowing GUI changes to be sent back to your MIDI controller. This keeps hardware with motorized faders, LED rings, or displays in sync with the software.

Feedback is automatically routed to the same MIDI device that was learned for each control, so multi-controller setups should work.

#### Enabling Feedback

Feedback is enabled by default. You can add a toggle button to your layout if you want:

```supercollider
learnGUI.setLayout(
    VLayout(
        HLayout(
            learnGUI.button(["Save"], \saveSettings, 90, 35),
            learnGUI.feedbackToggle,
        ),
        // ... rest of layout
    )
);
```

Or control it programmatically:

```supercollider
learnGUI.feedbackEnabled = true;   // Enable feedback
learnGUI.feedbackEnabled = false;  // Disable feedback
```

#### Syncing to Hardware

To sync all current GUI values to your controller (useful on startup or after loading a preset):

```supercollider
learnGUI.syncAllToHardware;
```

#### Per-Element Configuration

By default, feedback is sent on the same CC/channel that was learned. You can override this per-element:

```supercollider
// Disable feedback for a specific element
learnGUI.setElementFeedback(\freq, enabled: false);

// Override CC number and channel for an element
learnGUI.setElementFeedback(\pan, enabled: true, ccOverride: 10, chanOverride: 0);
```

#### Note-Based Feedback

For controllers that use note messages (e.g., button LEDs):

```supercollider
learnGUI.sendNoteFeedback(\myButton, value, noteNum: 60, chan: 0);
```

Feedback settings are automatically saved and loaded with your other MIDI mappings.
