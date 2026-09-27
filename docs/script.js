/* ==========================================================================
   Frontier — web preview audio engine
   15-band parametric EQ using Web Audio API BiquadFilter nodes.
   Source: pink noise generator (covers full audible spectrum 20Hz-20kHz).
   ========================================================================== */

(function () {
  "use strict";

  // ---- Constants --------------------------------------------------------
  // Standard 15-band ISO frequency centers (same as the Frontier app).
  const EQ_FREQS = [
    25, 40, 63, 100, 160, 250, 400, 630,
    1000, 1600, 2500, 4000, 6300, 10000, 16000
  ];

  const EQ_MIN_DB = -12;
  const EQ_MAX_DB = +12;
  const EQ_Q = 1.41;   // ~2/3 octave bandwidth, matches app default

  // EQ presets: 15-element arrays of dB gains, indexed to EQ_FREQS.
  const PRESETS = {
    flat:      [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    bass:      [9, 8, 7, 6, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0, 0],
    vocal:     [-3, -2, -1, 0, 1, 2, 3, 4, 4, 3, 2, 1, 0, -1, -2],
    presence:  [0, 0, 0, 0, 0, 0, 0, -1, 0, 1, 3, 5, 5, 4, 3],
    loudness:  [6, 5, 4, 3, 1, 0, -1, -2, -1, 0, 1, 2, 4, 5, 6]
  };

  // ---- State ------------------------------------------------------------
  let audioCtx = null;
  let pinkNoiseNode = null;
  let masterGain = null;
  let eqNodes = [];          // 15 BiquadFilterNodes (peaking)
  let isPlaying = false;
  let startTime = 0;         // AudioContext.currentTime when playback started
  let elapsed = 0;           // accumulated time before pause
  let rafId = null;          // requestAnimationFrame handle for progress
  let currentBandFlashTimer = null;

  // ---- DOM --------------------------------------------------------------
  const playBtn = document.getElementById("play-btn");
  const progressFill = document.getElementById("progress-fill");
  const timeCurrent = document.getElementById("time-current");
  const eqBandsContainer = document.getElementById("eq-bands");
  const eqStatus = document.getElementById("eq-status");
  const masterVolume = document.getElementById("master-volume");
  const presetButtons = document.querySelectorAll(".preset-btn");

  // ---- Audio setup ------------------------------------------------------
  function ensureAudioContext() {
    if (audioCtx) return;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    audioCtx = new Ctx();

    // Pink noise generator using ScriptProcessorNode (works in all browsers).
    // 4096-sample buffer, mono, refreshed each pass.
    const bufferSize = 4096;
    pinkNoiseNode = audioCtx.createScriptProcessor(bufferSize, 1, 1);
    let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    pinkNoiseNode.onaudioprocess = function (e) {
      const out = e.outputBuffer.getChannelData(0);
      for (let i = 0; i < bufferSize; i++) {
        const white = Math.random() * 2 - 1;
        b0 = 0.99886 * b0 + white * 0.0555179;
        b1 = 0.99332 * b1 + white * 0.0750759;
        b2 = 0.96900 * b2 + white * 0.1538520;
        b3 = 0.86650 * b3 + white * 0.3104856;
        b4 = 0.55000 * b4 + white * 0.5329522;
        b5 = -0.7616 * b5 - white * 0.0168980;
        out[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
        b6 = white * 0.115926;
      }
    };

    // Build the 15-band EQ chain
    eqNodes = EQ_FREQS.map(function (freq, i) {
      const filter = audioCtx.createBiquadFilter();
      if (i === 0) {
        // Lowest band: high-shelf for the bottom rolloff
        filter.type = "lowshelf";
      } else if (i === EQ_FREQS.length - 1) {
        // Highest band: high-shelf for the top air
        filter.type = "highshelf";
      } else {
        filter.type = "peaking";
        filter.Q.value = EQ_Q;
      }
      filter.frequency.value = freq;
      filter.gain.value = 0;
      return filter;
    });

    // Master gain
    masterGain = audioCtx.createGain();
    masterGain.gain.value = parseFloat(masterVolume.value) / 100 * 0.5; // 0.5 = headroom

    // Wire it up: pinkNoise -> eq[0] -> eq[1] -> ... -> eq[14] -> masterGain -> destination
    pinkNoiseNode.connect(eqNodes[0]);
    for (let i = 0; i < eqNodes.length - 1; i++) {
      eqNodes[i].connect(eqNodes[i + 1]);
    }
    eqNodes[eqNodes.length - 1].connect(masterGain);
    masterGain.connect(audioCtx.destination);
  }

  // ---- Playback ---------------------------------------------------------
  function startPlayback() {
    ensureAudioContext();
    if (audioCtx.state === "suspended") audioCtx.resume();
    // The ScriptProcessorNode always runs once connected; we just gate output via masterGain.
    masterGain.gain.cancelScheduledValues(audioCtx.currentTime);
    masterGain.gain.setValueAtTime(0.0001, audioCtx.currentTime);
    masterGain.gain.exponentialRampToValueAtTime(
      parseFloat(masterVolume.value) / 100 * 0.5,
      audioCtx.currentTime + 0.05
    );
    startTime = audioCtx.currentTime - elapsed;
    isPlaying = true;
    playBtn.classList.add("playing");
    tickProgress();
  }

  function pausePlayback() {
    if (!audioCtx) return;
    masterGain.gain.cancelScheduledValues(audioCtx.currentTime);
    masterGain.gain.setValueAtTime(masterGain.gain.value, audioCtx.currentTime);
    masterGain.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.05);
    elapsed = audioCtx.currentTime - startTime;
    isPlaying = false;
    playBtn.classList.remove("playing");
    if (rafId) cancelAnimationFrame(rafId);
  }

  function togglePlay() {
    if (isPlaying) pausePlayback();
    else startPlayback();
  }

  function tickProgress() {
    if (!isPlaying) return;
    const t = audioCtx.currentTime - startTime;
    // Wrap progress every 30s for visual feedback
    const pct = ((t % 30) / 30) * 100;
    progressFill.style.width = pct + "%";
    timeCurrent.textContent = formatTime(t);
    rafId = requestAnimationFrame(tickProgress);
  }

  function formatTime(seconds) {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return m + ":" + (s < 10 ? "0" : "") + s;
  }

  // ---- EQ bands ---------------------------------------------------------
  function formatFreq(hz) {
    if (hz >= 1000) {
      const k = hz / 1000;
      return (k % 1 === 0 ? k.toFixed(0) : k.toFixed(1)) + "k";
    }
    return String(hz);
  }

  function buildEqBands() {
    eqBandsContainer.innerHTML = "";
    EQ_FREQS.forEach(function (freq, i) {
      const wrap = document.createElement("div");
      wrap.className = "eq-band";

      const slider = document.createElement("input");
      slider.type = "range";
      slider.min = EQ_MIN_DB;
      slider.max = EQ_MAX_DB;
      slider.step = 1;
      slider.value = 0;
      slider.setAttribute("data-band", i);
      slider.setAttribute("aria-label", "EQ band " + freq + " Hz");
      slider.setAttribute("orient", "vertical");

      const gainLabel = document.createElement("div");
      gainLabel.className = "eq-band-gain";
      gainLabel.textContent = "0";

      const freqLabel = document.createElement("div");
      freqLabel.className = "eq-band-label";
      freqLabel.textContent = formatFreq(freq);

      // Update on input
      slider.addEventListener("input", function (e) {
        const db = parseFloat(e.target.value);
        setBandGain(i, db);
        gainLabel.textContent = (db > 0 ? "+" : "") + db.toFixed(0);
        flashBandStatus(freq, db);
        // User-touched: deactivate preset highlight
        presetButtons.forEach(function (b) { b.classList.remove("active"); });
      });

      wrap.appendChild(slider);
      wrap.appendChild(gainLabel);
      wrap.appendChild(freqLabel);
      eqBandsContainer.appendChild(wrap);
    });
  }

  function setBandGain(index, db) {
    if (!audioCtx) ensureAudioContext();
    const node = eqNodes[index];
    if (!node) return;
    node.gain.cancelScheduledValues(audioCtx.currentTime);
    node.gain.setValueAtTime(node.gain.value, audioCtx.currentTime);
    node.gain.linearRampToValueAtTime(db, audioCtx.currentTime + 0.04);
  }

  function flashBandStatus(freq, db) {
    if (currentBandFlashTimer) clearTimeout(currentBandFlashTimer);
    const sign = db > 0 ? "+" : "";
    const desc = describeBand(freq);
    eqStatus.textContent =
      formatFreq(freq) + "Hz · " + sign + db.toFixed(0) + " dB · " + desc;
    currentBandFlashTimer = setTimeout(function () {
      eqStatus.textContent = "Tap a slider to hear how that frequency band affects the sound.";
    }, 2500);
  }

  function describeBand(freq) {
    if (freq <= 60) return "sub bass — felt more than heard";
    if (freq <= 250) return "bass — body and warmth";
    if (freq <= 500) return "low-mids — fullness and mud";
    if (freq <= 2000) return "mids — vocals and instruments";
    if (freq <= 6000) return "presence — clarity and attack";
    return "brilliance — air and sparkle";
  }

  // ---- Presets ----------------------------------------------------------
  function applyPreset(name) {
    const gains = PRESETS[name];
    if (!gains) return;
    const sliders = eqBandsContainer.querySelectorAll('input[type="range"]');
    sliders.forEach(function (slider, i) {
      const db = gains[i];
      slider.value = db;
      setBandGain(i, db);
      const gainLabel = slider.parentElement.querySelector(".eq-band-gain");
      if (gainLabel) gainLabel.textContent = (db > 0 ? "+" : "") + db.toFixed(0);
    });
    presetButtons.forEach(function (b) {
      b.classList.toggle("active", b.dataset.preset === name);
    });
    eqStatus.textContent = "Preset: " + name.charAt(0).toUpperCase() + name.slice(1);
    if (currentBandFlashTimer) clearTimeout(currentBandFlashTimer);
    currentBandFlashTimer = setTimeout(function () {
      eqStatus.textContent = "Tap a slider to hear how that frequency band affects the sound.";
    }, 2500);
  }

  // ---- Wire up events ---------------------------------------------------
  function init() {
    buildEqBands();
    playBtn.addEventListener("click", togglePlay);
    masterVolume.addEventListener("input", function (e) {
      if (!audioCtx) return;
      const v = parseFloat(e.target.value) / 100 * 0.5;
      masterGain.gain.cancelScheduledValues(audioCtx.currentTime);
      masterGain.gain.setValueAtTime(masterGain.gain.value, audioCtx.currentTime);
      masterGain.gain.linearRampToValueAtTime(
        isPlaying ? Math.max(0.0001, v) : 0.0001,
        audioCtx.currentTime + 0.05
      );
    });
    presetButtons.forEach(function (btn) {
      btn.addEventListener("click", function () {
        applyPreset(btn.dataset.preset);
      });
    });

    // Keyboard shortcut: spacebar = play/pause
    document.addEventListener("keydown", function (e) {
      if (e.code === "Space" && document.activeElement.tagName !== "INPUT") {
        e.preventDefault();
        togglePlay();
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
