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

### Preset Bank

LearnGUI includes a 5x5 preset bank for storing and recalling GUI states. Each slot stores all current widget values.

#### Adding to Your Layout

```supercollider
learnGUI.setLayout(
    VLayout(
        HLayout(
            learnGUI.slider("Freq", \freq),
            learnGUI.slider("Pan", \pan),
            learnGUI.slider("Amp", \amp),
        ),
        HLayout(
            learnGUI.createPresetBank(),  // 5x5 grid (25 buttons) with default 30x30 size
            nil,
        ),
    )
);

// Signature: createPresetBank(numButtons, buttonSize, labels)

// Custom number of buttons (grid sized by square root):
learnGUI.createPresetBank(9)        // 3x3 grid
learnGUI.createPresetBank(16)       // 4x4 grid
learnGUI.createPresetBank(10)       // 4x3 grid (4 cols, fills remaining)

// Custom button size:
learnGUI.createPresetBank(25, 40)   // 25 buttons at 40x40 pixels

// With custom labels:
learnGUI.createPresetBank(10, 30, ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J"])

// Partial labels - buttons without labels are blank:
learnGUI.createPresetBank(8, 35, ["Init", "Warm", "Bright", "Dark"])  // last 4 are blank

// Using named arguments:
learnGUI.createPresetBank(numButtons: 9, buttonSize: 35, labels: ["Init", "Warm", "Bright"])
```

#### Usage

- **Shift-click** a button to store the current values into that preset slot
- **Click** a button to recall the stored values from that slot
- Empty slots appear dimmed; slots with stored data show in your `activeColor`
- Buttons are numbered 1-25

#### Programmatic Access

You can also access the preset data directly:

```supercollider
// Store current values to slot 0
learnGUI.presetBank[0] = learnGUI.getValues();

// Recall values from slot 0
learnGUI.setValues(learnGUI.presetBank[0]);

// Check if a slot has data
learnGUI.presetBank[5].notNil;  // true if slot 6 has stored values
```
