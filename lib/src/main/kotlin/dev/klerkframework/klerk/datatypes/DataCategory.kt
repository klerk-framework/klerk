package dev.klerkframework.klerk.datatypes

/**
 * What kind of data a [DataContainer] holds. Every container declares it, so that nothing is left unclassified.
 *
 * A container that is not [NonSensitive] never reveals its value by accident: [DataContainer.toString] returns a mask
 * for it, also to an actor that is allowed to read it. Code that is allowed to show the value reads it with
 * [DataContainer.value] or [DataContainer.valueOrNullIfNotAuthorized], which are checked against the authorization
 * rules.
 *
 * The category is a property of the container's type, so it applies wherever the type is used. An authorization rule
 * sees it as [DataContainer.category] of the property.
 */
public enum class DataCategory {
    /** Nothing in the value needs protection. [DataContainer.toString] shows it to those who may read it. */
    NonSensitive,

    /**
     * A secret, such as a password, a token or a key. It is masked by [DataContainer.toString], and it is never written to
     * the event log: the parameter is stored as a mask.
     */
    Sensitive,

    /**
     * Information about a person, such as a name, an email address or a national identity number. It is masked by
     * [DataContainer.toString]. The event log stores it, and erases it according to the `paramsAndExtra` of
     * [dev.klerkframework.klerk.EventLogRetention].
     */
    PersonalData,
}
