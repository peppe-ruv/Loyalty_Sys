package it.iren.loyalty.segmentservice.domain;

import java.util.List;

/** Porta verso il backoffice/CMS (ContentProvider): definizioni di segmento pubblicate. */
public interface SegmentSource {
    List<Segment> publishedSegments();
}
