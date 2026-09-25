class SttPcmCaptureProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.chunk = new Float32Array(4096);
        this.offset = 0;
        this.paused = false;

        // リアルタイム配信用: ダウンミックスしない生インターリーブPCM(Int16)を別経路で流す。
        // 既存のモノラルpath(chunk/offset/append/flush)には一切影響しない。
        this.rawStereoEnabled = false;
        this.rawChannels = 1;
        this.rawChunk = null;
        this.rawOffset = 0;

        this.port.onmessage = (event) => {
            const message = event.data || {};
            if (message.type === 'pause') {
                this.paused = !!message.value;
            } else if (message.type === 'flush') {
                this.flush();
                this.port.postMessage({ type: 'flushed' });
            } else if (message.type === 'raw_stereo') {
                this.rawStereoEnabled = !!message.value;
                this.rawChannels = message.channels === 2 ? 2 : 1;
                this.rawChunk = this.rawStereoEnabled ? new Int16Array(4096 * this.rawChannels) : null;
                this.rawOffset = 0;
            }
        };
    }

    flush() {
        if (this.offset > 0) {
            const completed = this.chunk.slice(0, this.offset);
            this.port.postMessage({ type: 'pcm', buffer: completed.buffer }, [completed.buffer]);
            this.offset = 0;
        }
        if (this.rawChunk && this.rawOffset > 0) {
            const completed = this.rawChunk.slice(0, this.rawOffset);
            this.port.postMessage({ type: 'raw_pcm', buffer: completed.buffer, channels: this.rawChannels }, [completed.buffer]);
            this.rawChunk = new Int16Array(this.rawChunk.length);
            this.rawOffset = 0;
        }
    }

    append(input) {
        let inputOffset = 0;
        while (inputOffset < input.length) {
            const count = Math.min(input.length - inputOffset, this.chunk.length - this.offset);
            this.chunk.set(input.subarray(inputOffset, inputOffset + count), this.offset);
            this.offset += count;
            inputOffset += count;

            if (this.offset === this.chunk.length) {
                const completed = this.chunk;
                this.port.postMessage({ type: 'pcm', buffer: completed.buffer }, [completed.buffer]);
                this.chunk = new Float32Array(4096);
                this.offset = 0;
            }
        }
    }

    appendRaw(ch0, ch1) {
        const channels = this.rawChannels;
        const n = ch0.length;
        for (let i = 0; i < n; i++) {
            const l = Math.max(-1, Math.min(1, ch0[i]));
            this.rawChunk[this.rawOffset++] = l * 32767;
            if (channels === 2) {
                const r = Math.max(-1, Math.min(1, ch1 ? ch1[i] : ch0[i]));
                this.rawChunk[this.rawOffset++] = r * 32767;
            }
            if (this.rawOffset === this.rawChunk.length) {
                const completed = this.rawChunk;
                this.port.postMessage({ type: 'raw_pcm', buffer: completed.buffer, channels }, [completed.buffer]);
                this.rawChunk = new Int16Array(completed.length);
                this.rawOffset = 0;
            }
        }
    }

    process(inputs, outputs) {
        const input = inputs[0];
        const output = outputs[0] && outputs[0][0];
        const ch0 = input && input[0];
        const ch1 = input && input[1];

        if (ch0) {
            let mono = ch0;
            if (ch1) {
                mono = new Float32Array(ch0.length);
                for (let i = 0; i < ch0.length; i++) mono[i] = (ch0[i] + ch1[i]) * 0.5;
            }
            if (!this.paused) {
                this.append(mono);
                if (this.rawStereoEnabled && this.rawChunk) this.appendRaw(ch0, ch1);
            }
            if (output) output.set(mono);
        } else if (output) {
            output.fill(0);
        }
        return true;
    }
}

registerProcessor('stt-pcm-capture', SttPcmCaptureProcessor);
