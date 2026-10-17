//! Error algebra — LOCK-STEP with app7_domain.h APP7_ERR_* and
//! App7Domain.hs `data DomainError`.  Adding a variant = adding a macro
//! there.  No exceptions.

/// The complete failure algebra of the domain, Phases 1–3.
/// Discriminants are the C ABI return codes (APP7_OK == 0 is "no error"
/// and deliberately NOT a variant — absence of error is `Ok(_)`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum DomainError {
    NotPermutation = 1,
    DegenerateCrop = 2,
    InvalidNormalizedCoord = 3,
    EmptyAnnotationPool = 4,
    InvalidSampleSize = 5,
    SessionNotStarted = 6,
    SessionAlreadyStarted = 7,
    NavigatorNotInitialized = 8,
    EmptyImageSequence = 9,
    IndexOutOfRange = 10,
    ImageIdNotFound = 11,
    ChapterOutOfRange = 12,
}

impl DomainError {
    /// Return-code projection (mirror of Haskell `domainErrorCode`).
    #[inline]
    pub const fn code(self) -> i32 {
        self as i32
    }

    /// Human-readable description, NUL-terminated for the C ABI.
    /// Mirror of domain-core/src/strerror.c (the constructor names of
    /// App7Domain.DomainError's `show` instance), byte for byte.
    pub const fn message_cstr(self) -> &'static [u8] {
        match self {
            DomainError::NotPermutation => b"NotPermutation\0",
            DomainError::DegenerateCrop => b"DegenerateCrop\0",
            DomainError::InvalidNormalizedCoord => b"InvalidNormalizedCoord\0",
            DomainError::EmptyAnnotationPool => b"EmptyAnnotationPool\0",
            DomainError::InvalidSampleSize => b"InvalidSampleSize\0",
            DomainError::SessionNotStarted => b"SessionNotStarted\0",
            DomainError::SessionAlreadyStarted => b"SessionAlreadyStarted\0",
            DomainError::NavigatorNotInitialized => b"NavigatorNotInitialized\0",
            DomainError::EmptyImageSequence => b"EmptyImageSequence\0",
            DomainError::IndexOutOfRange => b"IndexOutOfRange\0",
            DomainError::ImageIdNotFound => b"ImageIdNotFound\0",
            DomainError::ChapterOutOfRange => b"ChapterOutOfRange\0",
        }
    }

    /// Total decoding of a C return code.  `0` and unknown codes → None.
    pub const fn from_code(code: i32) -> Option<DomainError> {
        match code {
            1 => Some(DomainError::NotPermutation),
            2 => Some(DomainError::DegenerateCrop),
            3 => Some(DomainError::InvalidNormalizedCoord),
            4 => Some(DomainError::EmptyAnnotationPool),
            5 => Some(DomainError::InvalidSampleSize),
            6 => Some(DomainError::SessionNotStarted),
            7 => Some(DomainError::SessionAlreadyStarted),
            8 => Some(DomainError::NavigatorNotInitialized),
            9 => Some(DomainError::EmptyImageSequence),
            10 => Some(DomainError::IndexOutOfRange),
            11 => Some(DomainError::ImageIdNotFound),
            12 => Some(DomainError::ChapterOutOfRange),
            _ => None,
        }
    }
}

pub mod ffi {
    //! C ABI projection: `const char *app7_strerror(int code);`
    //! Strings verified byte-for-byte against domain-core/src/strerror.c.
    use super::DomainError;
    use core::ffi::c_char;

    const OK_MSG: &[u8] = b"OK\0";
    const UNKNOWN_MSG: &[u8] = b"UnknownError\0";

    #[no_mangle]
    pub extern "C" fn app7_strerror(code: i32) -> *const c_char {
        let bytes: &'static [u8] = if code == 0 {
            OK_MSG
        } else {
            match DomainError::from_code(code) {
                Some(e) => e.message_cstr(),
                None => UNKNOWN_MSG,
            }
        };
        bytes.as_ptr() as *const c_char
    }
}
