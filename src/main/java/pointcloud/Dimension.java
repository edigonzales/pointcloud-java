package pointcloud;

enum Dimension {
    X, Y, Z, Intensity, Classification, ReturnNumber, NumberOfReturns, GpsTime, Red, Green, Blue, Infrared;

    boolean available(int format) {
        return switch (this) {
            case GpsTime -> format == 1 || format == 3 || format >= 6;
            case Red, Green, Blue -> format == 2 || format == 3 || format >= 7;
            case Infrared -> format == 8;
            default -> true;
        };
    }

    double value(PointBatch batch, int point) {
        int start = batch.start(point);
        int format = batch.header.pointFormat();
        return switch (this) {
            case X -> batch.x(point);
            case Y -> batch.y(point);
            case Z -> batch.z(point);
            case Intensity -> batch.intensity(point);
            case Classification -> batch.classification(point);
            case ReturnNumber -> batch.returnNumber(point);
            case NumberOfReturns -> (batch.records.get(start + 14) >>> (format < 6 ? 3 : 4))
                    & (format < 6 ? 7 : 15);
            case GpsTime -> batch.records.getDouble(start + (format < 6 ? 20 : 22));
            case Red, Green, Blue, Infrared -> {
                int rgb = format == 2 ? 20 : format == 3 ? 28 : 30;
                yield batch.records.getChar(start + rgb + (ordinal() - Red.ordinal()) * 2);
            }
        };
    }

    static Dimension parse(String text) {
        try {
            return valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown dimension " + text);
        }
    }
}
