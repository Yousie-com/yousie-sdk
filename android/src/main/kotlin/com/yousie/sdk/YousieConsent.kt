package com.yousie.sdk

/**
 * Whether the user allows this install to be reported to Yousie.
 *
 * Yousie is a paid-attribution network, so what the SDK sends is advertising
 * data. Where the law asks for consent (the EEA, the UK, Switzerland), state
 * the user's answer here. Where it does not, pass [GRANTED].
 */
enum class YousieConsent {
    /** The install may be reported, and its subscription after it. */
    GRANTED,

    /**
     * The user said no. The click id is deleted and nothing is sent. This is
     * final for the install: a later [GRANTED] has nothing left to report.
     */
    DENIED,

    /**
     * Nobody has answered yet (the consent form has not been shown or is
     * still open). The click id waits on the phone, for a week at most, and
     * nothing is sent until [Yousie.setConsent] states an answer.
     */
    UNKNOWN,
}
