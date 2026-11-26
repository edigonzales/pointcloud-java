package pointcloud;

enum Dimension {
    X, Y, Z, Intensity, Classification, Synthetic, KeyPoint, Withheld, Overlap,
    ReturnNumber, NumberOfReturns, GpsTime, Red, Green, Blue, Infrared, HeightAboveGround;

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
            case Classification -> {
                int code = batch.classification(point);
                // PDAL exposes the old overlap class as class 0 with a separate overlap flag.
                yield format < 6 && code == 12 ? 0 : code;
            }
            case Synthetic -> (batch.records.get(start + 15) >>> (format < 6 ? 5 : 0)) & 1;
            case KeyPoint -> (batch.records.get(start + 15) >>> (format < 6 ? 6 : 1)) & 1;
            case Withheld -> (batch.records.get(start + 15) >>> (format < 6 ? 7 : 2)) & 1;
            case Overlap -> {
                if (format < 6) {
                    yield batch.classification(point) == 12 ? 1 : 0;
                }
                yield (batch.records.get(start + 15) >>> 3) & 1;
            }
            case ReturnNumber -> batch.returnNumber(point);
            case NumberOfReturns -> (batch.records.get(start + 14) >>> (format < 6 ? 3 : 4))
                    & (format < 6 ? 7 : 15);
            case GpsTime -> batch.records.getDouble(start + (format < 6 ? 20 : 22));
            case Red, Green, Blue, Infrared -> {
                int rgb = format == 2 ? 20 : format == 3 ? 28 : 30;
                yield batch.records.getChar(start + rgb + (ordinal() - Red.ordinal()) * 2);
            }
            case HeightAboveGround -> batch.heightAboveGround[point];
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
