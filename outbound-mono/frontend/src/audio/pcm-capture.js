// Audio-thread capture: 100 ms transport packets, with an explicit final partial packet.
class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super();
    this.samples = [];
    this.phase = 0;
    this.total = 0;
    this.stopped = false;
    this.port.onmessage = ({ data }) => {
      if (data?.type !== 'stop' || this.stopped) return;
      this.stopped = true;
      this.flush();
      this.port.postMessage({ type: 'drained', samples: this.total });
    };
  }
  flush() {
    if (!this.samples.length) return;
    const bytes = new ArrayBuffer(this.samples.length * 2);
    const view = new DataView(bytes);
    this.samples.forEach((sample, i) => view.setInt16(i * 2, sample, true));
    this.total += this.samples.length;
    this.samples = [];
    this.port.postMessage({ type: 'pcm', bytes }, [bytes]);
  }
  process(inputs) {
    if (this.stopped) return false;
    const samples = inputs[0]?.[0];
    if (!samples) return true;
    for (const sample of samples) {
      this.phase += 16000;
      if (this.phase < sampleRate) continue;
      this.phase -= sampleRate;
      this.samples.push(Math.max(-32768, Math.min(32767, Math.round(sample * 32768))));
      if (this.samples.length === 1600) this.flush();
    }
    return true;
  }
}
registerProcessor('trial-pcm-capture', PcmCapture);
