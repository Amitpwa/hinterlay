import java.io.*;
import java.nio.*;

/**
 * Generates the extra UI sounds (error, open, close, undo, detent) as 44.1 kHz mono 16-bit WAV files.
 * Run from the project root:  java tools/GenSounds.java app/src/main/res/raw
 * Everything is a few clean sine tones with a soft attack and an exponential decay, kept quiet and short.
 */
public class GenSounds {
    static final int RATE = 44100;

    interface Voice { double at(double t); }

    static short[] render(double seconds, Voice v, double gain) {
        int n = (int) (seconds * RATE);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double fadeOut = Math.min(1.0, (seconds - t) / 0.012); // never end with a click
            double s = v.at(t) * gain * Math.max(0, fadeOut);
            out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(s * 32767)));
        }
        return out;
    }

    static double env(double t, double attack, double decay) {
        double a = t < attack ? t / attack : 1.0;
        return a * Math.exp(-t / decay);
    }

    static void write(File f, short[] pcm) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes()).put("fmt ".getBytes());
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(pcm.length * 2);
        for (short s : pcm) b.putShort(s);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b.array()); }
    }

    // A pseudo-random source for the airy parts (fixed seed, so the files are the same every run).
    static long seed = 12345;
    static double noise() { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed >>> 33) / (double) (1L << 31)) * 2 - 1; }

    public static void main(String[] a) throws IOException {
        File dir = new File(a[0]);
        // Error: two soft falling notes, a gentle "not that" rather than a buzz.
        write(new File(dir, "fx_error.wav"), render(0.22, t -> {
            double f1 = 392.0, f2 = 311.1;
            double s1 = Math.sin(2 * Math.PI * f1 * t) * env(t, 0.004, 0.05);
            double t2 = t - 0.085;
            double s2 = t2 > 0 ? Math.sin(2 * Math.PI * f2 * t2) * env(t2, 0.004, 0.07) : 0;
            return (s1 + s2) * 0.6 + 0.12 * Math.sin(2 * Math.PI * f1 * 2 * t) * env(t, 0.004, 0.03);
        }, 0.55));
        // Open: a short airy rise (filtered noise under a gliding tone).
        write(new File(dir, "fx_open.wav"), render(0.16, t -> {
            double p = t / 0.16;
            double f = 330 + 260 * p * p;
            double tone = Math.sin(2 * Math.PI * (330 * t + 260 * t * p * p / 3 * 0.16 * 0 + 0) + 2 * Math.PI * (130 * t * p)) ;
            double air = noise() * 0.18 * Math.sin(Math.PI * p);
            return (Math.sin(2 * Math.PI * f * t) * 0.35 * Math.sin(Math.PI * Math.min(1, p * 1.1)) + air + tone * 0.0);
        }, 0.5));
        // Close: the same shape falling, a little shorter and quieter.
        write(new File(dir, "fx_close.wav"), render(0.12, t -> {
            double p = t / 0.12;
            double f = 520 - 190 * p;
            double air = noise() * 0.14 * Math.sin(Math.PI * p);
            return Math.sin(2 * Math.PI * f * t) * 0.30 * Math.sin(Math.PI * Math.min(1, p * 1.1)) + air;
        }, 0.45));
        // Undo: one quick falling blip.
        write(new File(dir, "fx_undo.wav"), render(0.09, t -> {
            double f = 620 - 2200 * t;
            return Math.sin(2 * Math.PI * f * t) * env(t, 0.003, 0.03);
        }, 0.5));
        // Detent: a tiny dry tick for sliders passing the middle (rarely used; a short click with almost no tone).
        write(new File(dir, "fx_detent.wav"), render(0.03, t -> {
            return (Math.sin(2 * Math.PI * 1500 * t) * 0.6 + noise() * 0.2) * env(t, 0.0008, 0.006);
        }, 0.45));
        System.out.println("written to " + dir.getAbsolutePath());
    }
}
