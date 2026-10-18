"""Generate silent, playable PCM WAV fixtures using only the Python standard library."""
from pathlib import Path
import wave


def main():
    destination = Path(__file__).resolve().parents[1] / "debug-audio"
    destination.mkdir(exist_ok=True)
    sample_rate = 44100
    silence = bytes(sample_rate * 2)  # One second, mono, signed 16-bit PCM.
    for name, minutes in (("debug_a", 1), ("debug_b", 2), ("debug_c", 3)):
        output = destination / f"{name}.wav"
        if output.exists():
            raise FileExistsError(f"Refusing to overwrite {output}")
        with wave.open(str(output), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            for _ in range(minutes * 60):
                wav.writeframesraw(silence)
        with wave.open(str(output), "rb") as wav:
            assert wav.getnframes() == sample_rate * minutes * 60
            assert wav.getnchannels() == 1 and wav.getsampwidth() == 2
            while data := wav.readframes(sample_rate):
                assert not any(data), "Expected silent PCM samples"
            print(f"{output.name}: {minutes * 60}s, {output.stat().st_size} bytes, silence verified")


if __name__ == "__main__":
    main()
