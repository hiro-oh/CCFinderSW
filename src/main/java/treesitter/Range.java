package treesitter;

public class Range {
    public final int startByte;
    public final int endByte;

    public Range(int startByte, int endByte) {
        this.startByte = startByte;
        this.endByte = endByte;
    }

    public boolean contains(int offset) {
        return startByte <= offset && offset < endByte;
    }
}
