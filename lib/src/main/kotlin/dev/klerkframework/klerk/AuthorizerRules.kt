package dev.klerkframework.klerk

/**
 * Return type of a positive authorization rule. An operation is allowed only if at least one positive rule
 * returns [Allow] and no negative rule returns [NegativeAuthorization.Deny]. See docs/authorization.md.
 */
public enum class PositiveAuthorization {
    /** This rule does not grant access; other rules may still allow the operation. */
    NoOpinion,

    /** This rule grants access. */
    Allow,
}

/**
 * Return type of a negative authorization rule. A single [Deny] from any negative rule rejects the operation
 * regardless of what positive rules say. See docs/authorization.md.
 */
public enum class NegativeAuthorization {
    /** This rule does not object; other rules may still deny the operation. */
    Pass,

    /** This rule rejects the operation outright. */
    Deny,
}
