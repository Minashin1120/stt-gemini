class SttPcmCaptureProcessor extends AudioWorkletProcessor {
    constructor() {
        super();
        this.chunk = new Float32Array(4096);
        this.offset = 0;
        this.paused = false;

        this.port.onmessage = (event) => {
            const message = event.data || {};
            if (message.type === 'pause') {
                this.paused = !!message.value;
            } else if (message.type === 'flush') {
                this.flush();
                this.port.postMessage({ type: 'flushed' });
            }
        };
    }

    flush() {
        if (this.offset === 0) return;
        const completed = this.chunk.slice(0, this.offset);
        this.port.postMessage({ type: 'pcm', buffer: completed.buffer }, [completed.buffer]);
        this.offset = 0;
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

    process(inputs, outputs) {
        const input = inputs[0] && inputs[0][0];
        const output = outputs[0] && outputs[0][0];

        if (input) {
            if (!this.paused) this.append(input);
            if (output) output.set(input);
        } else if (output) {
            output.fill(0);
        }
        return true;
    }
}

registerProcessor('stt-pcm-capture', SttPcmCaptureProcessor);
