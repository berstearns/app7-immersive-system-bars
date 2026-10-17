-- ════════════════════════════════════════════════════════════════════════
--  Test.hs  —  round-trip assertions for App7Domain Phase-1 predicates.
--
--  No external deps (no hunit, no aeson) — just hardcoded cases against
--  the expected outputs from the Kotlin originals.  Exit 0 = green.
--
--  Each block of asserts corresponds to one Kotlin file the algebra
--  mirrors.  Boundary cases (off-by-one, empty inputs, NaN-adjacent
--  values) are pulled from the cascade audit checklist (INSTRUCTION.md §3).
-- ════════════════════════════════════════════════════════════════════════
module Main where

import App7Domain
import System.Exit (exitSuccess, exitWith, ExitCode(..))
import Data.IORef (newIORef, readIORef, modifyIORef')
import Data.List (nub)

approx :: Float -> Float -> Bool
approx a b = abs (a - b) < 1e-5

main :: IO ()
main = do
  failed <- newIORef (0 :: Int)
  total  <- newIORef (0 :: Int)

  let assert label cond = do
        modifyIORef' total (+ 1)
        if cond
          then putStrLn ("  ok  " ++ label)
          else do
            modifyIORef' failed (+ 1)
            putStrLn ("  FAIL " ++ label)

  putStrLn "── App7Domain Phase 1 round-trip assertions ──"

  ----------------------------------------------------------------
  putStrLn "[CropSelection — normalizedX/Y/Width/Height]"
  let c1 = CropSelection 0.5 0.2 0.1 0.4   -- start > end on x, < on y
  assert "normalizedX swaps when start>end"  (approx (normalizedX c1) 0.1)
  assert "normalizedY keeps when start<end"  (approx (normalizedY c1) 0.2)
  assert "normalizedWidth = abs(dx) = 0.4"   (approx (normalizedWidth  c1) 0.4)
  assert "normalizedHeight = abs(dy) = 0.2"  (approx (normalizedHeight c1) 0.2)

  ----------------------------------------------------------------
  putStrLn "[CropSelection — centerX/Y]"
  let c2 = CropSelection 0.2 0.2 0.6 0.8
  assert "centerX = 0.4" (approx (centerX c2) 0.4)
  assert "centerY = 0.5" (approx (centerY c2) 0.5)

  ----------------------------------------------------------------
  putStrLn "[CropSelection — isDegenerate at minNormalizedSize=0.02]"
  let tiny = CropSelection 0.10 0.10 0.105 0.105
  let big  = CropSelection 0.00 0.00 0.50  0.50
  let edge = CropSelection 0.00 0.00 0.02  0.02   -- exactly equal to threshold = NOT degenerate
  let just = CropSelection 0.00 0.00 0.019 0.50   -- one axis under, other big -> degenerate
  assert "tiny is degenerate"             (isDegenerate minNormalizedSize tiny)
  assert "big is NOT degenerate"     (not (isDegenerate minNormalizedSize big))
  assert "edge (==0.02) is NOT degenerate (strict <)"
                                     (not (isDegenerate minNormalizedSize edge))
  assert "one-axis-under IS degenerate"   (isDegenerate minNormalizedSize just)

  ----------------------------------------------------------------
  putStrLn "[CropSelection — clamped]"
  let oob = CropSelection (-0.5) 1.5 1.2 (-0.2)
  let clp = clamped oob
  assert "clamped startX -0.5 -> 0"  (approx (csStartX clp) 0)
  assert "clamped startY  1.5 -> 1"  (approx (csStartY clp) 1)
  assert "clamped endX    1.2 -> 1"  (approx (csEndX   clp) 1)
  assert "clamped endY   -0.2 -> 0"  (approx (csEndY   clp) 0)
  let already = CropSelection 0.1 0.2 0.3 0.4
  assert "clamped is idempotent on in-range" (clamped (clamped already) == clamped already)

  ----------------------------------------------------------------
  putStrLn "[ConversationBox — contains]"
  let box = ConversationBox 0.1 0.1 0.4 0.4 Bubble Nothing Nothing Nothing
  assert "tap inside box (0.3,0.3)"               (contains 0.3 0.3 box)
  assert "tap outside box left (0.05,0.3)"   (not (contains 0.05 0.3 box))
  assert "tap outside box below (0.3,0.6)"   (not (contains 0.3 0.6 box))
  assert "tap on left edge (0.1,0.3) inclusive"   (contains 0.1 0.3 box)
  assert "tap on bottom-right corner inclusive"   (contains 0.5 0.5 box)

  ----------------------------------------------------------------
  putStrLn "[PageBubbleOrdering — isValidPermutation]"
  assert "[0,1,2] / 3 valid"          (isValidPermutation [0,1,2] 3)
  assert "[2,0,1] / 3 valid (shuffled)" (isValidPermutation [2,0,1] 3)
  assert "[] / 0 valid (empty)"       (isValidPermutation [] 0)
  assert "[0,1] / 3 invalid (count mismatch)"  (not (isValidPermutation [0,1] 3))
  assert "[0,1,3] / 3 invalid (gap at 2)"      (not (isValidPermutation [0,1,3] 3))
  assert "[0,0,1] / 3 invalid (duplicate)"     (not (isValidPermutation [0,0,1] 3))
  assert "[0,1,2,3] / 3 invalid (overflow)"    (not (isValidPermutation [0,1,2,3] 3))

  ----------------------------------------------------------------
  putStrLn "[DomainError — code projection (Phase-2 lock-step)]"
  assert "NotPermutation = 1"           (domainErrorCode NotPermutation == 1)
  assert "DegenerateCrop = 2"           (domainErrorCode DegenerateCrop == 2)
  assert "ChapterOutOfRange = 12"       (domainErrorCode ChapterOutOfRange == 12)
  let codes = map domainErrorCode allErrors
  assert "all 12 codes distinct" (length codes == length (nub codes))

  ----------------------------------------------------------------
  putStrLn "[DefaultAnnotationPool — must match Kotlin RandomSamplingDecisionFunction]"
  assert "pool size = 17" (defaultAnnotationPoolSize == 17)
  assert "first entry = (understood, Got it)"
    (head defaultAnnotationPool == ("understood", "Got it"))
  assert "last entry = (callback, Callback)"
    (last defaultAnnotationPool == ("callback", "Callback"))
  let labels   = map fst defaultAnnotationPool
  let displays = map snd defaultAnnotationPool
  assert "all labels distinct"   (length labels   == length (nub labels))
  assert "all displays distinct" (length displays == length (nub displays))

  ----------------------------------------------------------------
  f <- readIORef failed
  t <- readIORef total
  putStrLn ("── " ++ show (t - f) ++ "/" ++ show t ++ " passed ──")
  if f == 0
    then exitSuccess
    else exitWith (ExitFailure 1)

allErrors :: [DomainError]
allErrors =
  [ NotPermutation, DegenerateCrop, InvalidNormalizedCoord
  , EmptyAnnotationPool, InvalidSampleSize
  , SessionNotStarted, SessionAlreadyStarted
  , NavigatorNotInitialized, EmptyImageSequence, IndexOutOfRange
  , ImageIdNotFound, ChapterOutOfRange
  ]
