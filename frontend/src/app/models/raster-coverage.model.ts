export interface GeoJsonPolygon {
  type: 'Polygon';
  coordinates: number[][][];
}

export interface RasterCoverage {
  id: number;
  cellId: string;
  filePath: string;
  bbox: GeoJsonPolygon;
  crs: string;
  svr: boolean;
  visible: boolean;
  createdAt: string;
}

export interface ToggleResponse {
  cellId: string;
  visible: boolean;
  updatedCount: number;
}
