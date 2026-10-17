-- ════════════════════════════════════════════════════════════════════════
--  App7Domain.hs  —  algebraic source of truth for app7's pure domain.
--
--  Mirrors (and will replace via JNI/native loader):
--    shared/src/commonMain/kotlin/pl/czak/learnlauncher/domain/model/
--      RegionType.kt, CropSelection.kt, ConversationBox.kt,
--      PageBubbleOrdering.kt, RegionAnnotation.kt, DoubleTapAction.kt
--
--  The Haskell algebra here is the SOURCE OF TRUTH.  The shipped binary
--  is `domain-core/*.c` (a hand-translation, NOT GHC's output — see
--  ffi-export-for-android.md §4 for why).  This file's job is to hold
--  the algebra and serve as a round-trip oracle for ParityTest.
--
--  Cascade rule (from sla-ddd-to-c/docs/auto-docs/INSTRUCTION.md §3):
--    TYPE → RULE → WIRE → CONSUMER → STORAGE → DATA → TESTS
--  Any rule change here must propagate in that order.
-- ════════════════════════════════════════════════════════════════════════
module App7Domain where

import Data.List (sort)

-- ── 1. ENTITY TYPES (THE ALGEBRA) ───────────────────────────────────────

data RegionType = Bubble | Token | Crop
  deriving (Eq, Show)

data CropSelection = CropSelection
  { csStartX :: !Float
  , csStartY :: !Float
  , csEndX   :: !Float
  , csEndY   :: !Float
  } deriving (Eq, Show)

data OcrToken = OcrToken
  { otText       :: !String
  , otConfidence :: !Float
  , otX, otY     :: !Float
  , otWidth      :: !Float
  , otHeight     :: !Float
  } deriving (Eq, Show)

data ConversationBox = ConversationBox
  { cbX, cbY                :: !Float
  , cbWidth, cbHeight       :: !Float
  , cbRegionType            :: !RegionType
  , cbParentBubbleIndex     :: !(Maybe Int)
  , cbTokenIndex            :: !(Maybe Int)
  , cbText                  :: !(Maybe String)
  } deriving (Eq, Show)

data DoubleTapAction
  = AnnotateBubble
      { dtaNx :: !Float, dtaNy :: !Float
      , dtaBox :: !ConversationBox
      , dtaBoxIndex :: !Int
      }
  | MarkUnderstoodAndAdvance
  | GoToPrevious
  | AnnotateToken
      { dtaTokenNx :: !Float, dtaTokenNy :: !Float
      , dtaToken :: !ConversationBox
      , dtaParentBubbleIndex :: !Int
      , dtaTokenIndex :: !Int
      }
  deriving (Eq, Show)

-- Pure data carrier; Room CHECK constraints in
-- shared/src/androidMain/.../data/db/entity/Entities.kt mirror these.
data RegionAnnotation = RegionAnnotation
  { raImageId            :: !String
  , raBoxIndex           :: !Int
  , raBoxX, raBoxY       :: !Float
  , raBoxWidth, raBoxHeight :: !Float
  , raLabel              :: !String
  , raTimestamp          :: !Integer  -- epoch ms; Kotlin Long
  , raTapX, raTapY       :: !Float
  , raRegionType         :: !RegionType
  , raParentBubbleIndex  :: !(Maybe Int)
  , raTokenIndex         :: !(Maybe Int)
  } deriving (Eq, Show)

-- ── 2. ERROR ALGEBRA ────────────────────────────────────────────────────
-- Forward-looking catalog: every failure site we expect across Phases 1–3.
-- Lock-step with domain-core/include/app7_domain.h (Phase 2):
--   APP7_OK                          = 0
--   APP7_ERR_NOT_PERMUTATION         = 1
--   APP7_ERR_DEGENERATE_CROP         = 2
--   APP7_ERR_INVALID_NORMALIZED_COORD= 3
--   APP7_ERR_EMPTY_ANNOTATION_POOL   = 4
--   APP7_ERR_INVALID_SAMPLE_SIZE     = 5
--   APP7_ERR_SESSION_NOT_STARTED     = 6
--   APP7_ERR_SESSION_ALREADY_STARTED = 7
--   APP7_ERR_NAVIGATOR_NOT_INITIALIZED = 8
--   APP7_ERR_EMPTY_IMAGE_SEQUENCE    = 9
--   APP7_ERR_INDEX_OUT_OF_RANGE      = 10
--   APP7_ERR_IMAGE_ID_NOT_FOUND      = 11
--   APP7_ERR_CHAPTER_OUT_OF_RANGE    = 12
-- Adding a constructor here = adding a macro there.  No exceptions.
data DomainError
  = NotPermutation
  | DegenerateCrop
  | InvalidNormalizedCoord
  | EmptyAnnotationPool
  | InvalidSampleSize
  | SessionNotStarted
  | SessionAlreadyStarted
  | NavigatorNotInitialized
  | EmptyImageSequence
  | IndexOutOfRange
  | ImageIdNotFound
  | ChapterOutOfRange
  deriving (Eq, Show)

-- Return-code projection.  Keep in lock-step with the macros above.
domainErrorCode :: DomainError -> Int
domainErrorCode NotPermutation           = 1
domainErrorCode DegenerateCrop           = 2
domainErrorCode InvalidNormalizedCoord   = 3
domainErrorCode EmptyAnnotationPool      = 4
domainErrorCode InvalidSampleSize        = 5
domainErrorCode SessionNotStarted        = 6
domainErrorCode SessionAlreadyStarted    = 7
domainErrorCode NavigatorNotInitialized  = 8
domainErrorCode EmptyImageSequence       = 9
domainErrorCode IndexOutOfRange          = 10
domainErrorCode ImageIdNotFound          = 11
domainErrorCode ChapterOutOfRange        = 12

-- ── 3. CONSTANTS ────────────────────────────────────────────────────────

minNormalizedSize :: Float
minNormalizedSize = 0.02   -- mirrors CropSelection.MIN_NORMALIZED_SIZE

-- ── 4. PURE PREDICATES — CropSelection ──────────────────────────────────

normalizedX, normalizedY :: CropSelection -> Float
normalizedX (CropSelection sx _ ex _) = min sx ex
normalizedY (CropSelection _ sy _ ey) = min sy ey

normalizedWidth, normalizedHeight :: CropSelection -> Float
normalizedWidth  (CropSelection sx _ ex _) = abs (ex - sx)
normalizedHeight (CropSelection _ sy _ ey) = abs (ey - sy)

centerX, centerY :: CropSelection -> Float
centerX c = normalizedX c + normalizedWidth c / 2
centerY c = normalizedY c + normalizedHeight c / 2

isDegenerate :: Float -> CropSelection -> Bool
isDegenerate ms c = normalizedWidth c < ms || normalizedHeight c < ms

clamp01 :: Float -> Float
clamp01 = max 0 . min 1

clamped :: CropSelection -> CropSelection
clamped (CropSelection sx sy ex ey) =
  CropSelection (clamp01 sx) (clamp01 sy) (clamp01 ex) (clamp01 ey)

-- ── 5. PURE PREDICATES — ConversationBox ────────────────────────────────

contains :: Float -> Float -> ConversationBox -> Bool
contains nx ny (ConversationBox x y w h _ _ _ _) =
  nx >= x && nx <= x + w && ny >= y && ny <= y + h

-- ── 6. PURE PREDICATES — PageBubbleOrdering ─────────────────────────────

isValidPermutation :: [Int] -> Int -> Bool
isValidPermutation indices n =
  length indices == n && sort indices == [0 .. n - 1]

-- ── 7. ANNOTATION POOL — the algebraic data that drives the sampler ─────
-- Mirror of DefaultAnnotationPool in
--   shared/src/commonMain/kotlin/.../domain/annotation/RandomSamplingDecisionFunction.kt
-- The actual sampler (xoshiro256** + Fisher-Yates partial shuffle) is
-- implemented in domain-core/src/annotation_pool.c and verified via
-- round_trip.c semantic-property tests rather than a Haskell port of the
-- RNG: the algebra here is the POOL (pure data); the shuffle is procedural
-- glue with no surface area worth porting twice.
defaultAnnotationPool :: [(String, String)]
defaultAnnotationPool =
  [ ("understood",         "Got it")
  , ("partially",          "Partial")
  , ("not_understood",     "Didn't get it")
  , ("need_re_read",       "Re-read")
  , ("key_idiom",          "Idiom")
  , ("vocabulary",         "Vocab")
  , ("grammar",            "Grammar")
  , ("surprising",         "Surprising")
  , ("funny",              "Funny")
  , ("sad",                "Sad")
  , ("agree",              "Agree")
  , ("disagree",           "Disagree")
  , ("confused",           "Confused")
  , ("follow_up_question", "Question")
  , ("character_intro",    "Character")
  , ("plot_beat",          "Plot beat")
  , ("callback",           "Callback")
  ]

defaultAnnotationPoolSize :: Int
defaultAnnotationPoolSize = length defaultAnnotationPool
