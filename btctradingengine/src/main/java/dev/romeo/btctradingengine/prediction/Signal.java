package dev.romeo.btctradingengine.prediction;

public enum Signal {
    BUY(1),
    SELL(-1),
    HOLD(0);

    private final int value;

    Signal(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

