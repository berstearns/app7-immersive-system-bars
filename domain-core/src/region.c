/* region.c — C projection of App7Domain.hs ConversationBox.contains.
 *
 * Inclusive on all four edges, matching Kotlin:
 *   nx >= x && nx <= x + width && ny >= y && ny <= y + height
 */
#include "app7_domain.h"

int app7_box_contains(float nx, float ny,
                      float bx, float by, float bw, float bh) {
    return (nx >= bx) && (nx <= bx + bw)
        && (ny >= by) && (ny <= by + bh);
}
