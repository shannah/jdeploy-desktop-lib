package ca.weblite.jdeploy.app.hive;

import java.util.Map;

/**
 * Response to a ping from another instance.
 *
 * <p>Contains information about the responding instance, including
 * its unique identifier and optional metadata properties.</p>
 */
public interface HivePong {

    /**
     * Returns the unique identifier of the responding instance.
     *
     * @return the instance ID, never null
     */
    String getInstanceId();

    /**
     * Returns optional metadata properties from the responding instance.
     *
     * <p>Properties are set via {@link Hive#setInstanceProperties(Map)}
     * and can include information like app version, state, or capabilities.</p>
     *
     * @return the properties map, never null but may be empty
     */
    Map<String, String> getProperties();

    /**
     * Convenience method to get a single property value.
     *
     * @param key the property key
     * @return the property value, or null if not present
     */
    default String getProperty(String key) {
        return getProperties().get(key);
    }
}
