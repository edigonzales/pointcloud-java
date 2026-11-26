# Reference canopy surfaces and point segmentation for the Solothurn experiment.
args <- commandArgs(trailingOnly = TRUE)
lidR::set_lidr_threads(1)
data.table::setDTthreads(1)

read_points <- function(path) {
    las <- lidR::readLAS(path)
    heights <- las@data$HeightAboveGround
    stopifnot(is.numeric(heights), length(heights) > 0, all(is.finite(heights)), all(heights >= 5))
    las
}

write_grid <- function(raster, path, template) {
    stopifnot(terra::compareGeom(raster, template))
    terra::writeRaster(raster, path, datatype = "FLT8S", NAflag = -9999)
}

surfaces <- function(java, official_25cm, dem, points, output) {
    cat(paste("R", getRversion(), "lidR", packageVersion("lidR"), "terra", packageVersion("terra"),
              "lidaRtRee", packageVersion("lidaRtRee"), "\n"))
    chm <- terra::rast(file.path(java, "chm.tif"))
    dsm <- terra::rast(file.path(java, "dsm.tif"))
    stopifnot(terra::compareGeom(chm, dsm))
    official <- terra::aggregate(terra::rast(official_25cm), fact = 2, fun = "max", na.rm = TRUE)
    write_grid(official, file.path(output, "vhm.tif"), chm)

    las <- read_points(points)
    limits <- as.vector(terra::ext(chm))
    first <- lidR::filter_poi(las, ReturnNumber == 1L, X >= limits[1], X < limits[2],
                            Y >= limits[3], Y < limits[4])
    count <- nrow(first@data)
    stopifnot(count > 0)
    data.table::set(first@data, j = "Z", value = first@data$HeightAboveGround)
    spikefree <- lidR::rasterize_canopy(first, res = chm, algorithm = lidR::lspikefree(), pkg = "terra")
    write_grid(spikefree, file.path(output, "lspikefree.tif"), chm)
    cell_dtm <- terra::resample(terra::rast(dem), dsm, method = "near")
    stopifnot(terra::compareGeom(cell_dtm, dsm), all(is.finite(terra::values(cell_dtm))))

    apex_cells <- function(raster, terrain, method) {
        treetops <- lidaRtRee::tree_segmentation(raster, dtm = terrain, jitter = FALSE, nl_filter = "Closing",
                                               nl_size = 5, sigma = 0.3, hmin = 5, dmin = 0, dprop = 0.05,
                                               max.width = 11)
        cells <- which(terra::values(treetops$local_maxima) > 0)
        data.frame(method = rep(method, length(cells)), cell = cells)
    }
    apexes <- rbind(apex_cells(chm, NULL, "lidartree-chm"), apex_cells(dsm, cell_dtm, "lidartree-dsm"))
    write.csv(apexes, file.path(output, "lidartree-cells.csv"), row.names = FALSE)
    area <- diff(limits[1:2]) * diff(limits[3:4])
    write.csv(data.frame(first_returns = count, spacing_m = sqrt(area / count)),
              file.path(output, "lspikefree-input.csv"), row.names = FALSE)
}

li2012 <- function(points, result, counts) {
    las <- read_points(points)
    raw <- las@data
    height <- raw$HeightAboveGround
    voxels <- data.table::data.table(x = floor(raw$X * 2), y = floor(raw$Y * 2), z = floor(height),
                                    height = height, row = seq_along(height))
    data.table::setorder(voxels, x, y, z, -height, row)
    keep <- sort(voxels$row[!duplicated(voxels, by = c("x", "y", "z"))])
    source <- raw[keep]
    # Retain the original elevations for the CSV and classify in full-precision HAG space.
    normalized <- lidR::LAS(data.table::copy(source), header = las@header, check = FALSE)
    data.table::set(normalized@data, j = "Z", value = source$HeightAboveGround)
    trees <- lidR::segment_trees(normalized, lidR::li2012(hmin = 5))
    stopifnot(identical(trees@data$X, source$X), identical(trees@data$Y, source$Y),
              identical(trees@data$Z, source$HeightAboveGround))
    ids <- trees@data$treeID
    valid <- which(!is.na(ids) & ids > 0L)
    ranked <- valid[order(ids[valid], -source$HeightAboveGround[valid], valid)]
    winners <- ranked[!duplicated(ids[ranked])]
    exact <- function(values) sprintf("%.17g", values)
    apexes <- data.frame(easting = exact(source$X[winners]), northing = exact(source$Y[winners]),
                         z = exact(source$Z[winners]), height_above_ground = exact(source$HeightAboveGround[winners]))
    write.table(apexes, result, row.names = FALSE, sep = ",", quote = FALSE)
    write.csv(data.frame(returns = nrow(raw), voxels = length(keep), segments = length(winners)),
              counts, row.names = FALSE)
}

if (length(args) == 6L && args[1] == "surfaces") {
    do.call(surfaces, as.list(args[-1]))
} else if (length(args) == 4L && args[1] == "li2012") {
    do.call(li2012, as.list(args[-1]))
} else {
    stop("Expected surfaces JAVA VHM25 DEM POINTS OUTPUT or li2012 POINTS OUTPUT COUNTS")
}
