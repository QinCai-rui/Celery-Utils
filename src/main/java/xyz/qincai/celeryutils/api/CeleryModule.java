package xyz.qincai.celeryutils.api;

/**
 * Base interface for all modules in CeleryUtils.
 * Each module must implement this interface to be loaded by the plugin.
 */
public interface CeleryModule {
    
    /**
     * Gets the name of this module
     * @return module name
     */
    String getName();
    
    /**
     * Initializes the module
     * @return true if initialization was successful
     */
    boolean initialize();
    
    /**
     * Disables the module
     */
    void disable();
    
    /**
     * Checks if the module is enabled
     * @return true if enabled
     */
    boolean isEnabled();
}
