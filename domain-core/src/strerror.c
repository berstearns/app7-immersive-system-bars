/* strerror.c — string projection of the APP7_ERR_* return-code enum.
 *
 * Mirrors App7Domain.DomainError's `show` instance.  Useful for debug
 * logging and test failure messages; not part of the hot path.
 */
#include "app7_domain.h"

const char *app7_strerror(int code) {
    switch (code) {
    case APP7_OK:                             return "OK";
    case APP7_ERR_NOT_PERMUTATION:            return "NotPermutation";
    case APP7_ERR_DEGENERATE_CROP:            return "DegenerateCrop";
    case APP7_ERR_INVALID_NORMALIZED_COORD:   return "InvalidNormalizedCoord";
    case APP7_ERR_EMPTY_ANNOTATION_POOL:      return "EmptyAnnotationPool";
    case APP7_ERR_INVALID_SAMPLE_SIZE:        return "InvalidSampleSize";
    case APP7_ERR_SESSION_NOT_STARTED:        return "SessionNotStarted";
    case APP7_ERR_SESSION_ALREADY_STARTED:    return "SessionAlreadyStarted";
    case APP7_ERR_NAVIGATOR_NOT_INITIALIZED:  return "NavigatorNotInitialized";
    case APP7_ERR_EMPTY_IMAGE_SEQUENCE:       return "EmptyImageSequence";
    case APP7_ERR_INDEX_OUT_OF_RANGE:         return "IndexOutOfRange";
    case APP7_ERR_IMAGE_ID_NOT_FOUND:         return "ImageIdNotFound";
    case APP7_ERR_CHAPTER_OUT_OF_RANGE:       return "ChapterOutOfRange";
    default:                                  return "UnknownError";
    }
}
