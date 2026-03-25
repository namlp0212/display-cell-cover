-- Remove duplicate SVR records; pipeline now uses only is_svr=false records
DELETE FROM raster_coverages WHERE is_svr = true;
