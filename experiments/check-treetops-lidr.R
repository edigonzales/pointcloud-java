args <- commandArgs(trailingOnly = TRUE)
lidR::set_lidr_threads(1)
cat("lidR", as.character(packageVersion("lidR")),
    ", terra", as.character(packageVersion("terra")), "\n")
for (folder in args) {
    tables <- list()
    for (surface in c("chm", "dsm")) {
        raster <- terra::rast(file.path(folder, paste0(surface, ".tif")))
        for (radius in c(2, 3, 4)) {
            tips <- lidR::locate_trees(raster, lidR::lmf(2 * radius, hmin = 0, shape = "circular"))
            xy <- sf::st_coordinates(tips)[, c("X", "Y"), drop = FALSE]
            tables[[paste0(surface, "-r", radius)]] <- terra::cellFromXY(raster, xy)
        }
    }
    rows <- data.frame(method = rep(names(tables), lengths(tables)), cell = unlist(tables, use.names = FALSE))
    write.csv(rows, file.path(folder, "lidr.csv"), row.names = FALSE)
}
