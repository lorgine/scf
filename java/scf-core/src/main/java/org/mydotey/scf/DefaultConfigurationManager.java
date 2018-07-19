package org.mydotey.scf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author koqizhao
 *
 * May 16, 2018
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class DefaultConfigurationManager implements ConfigurationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConfigurationManager.class);

    protected static final Comparator<Integer> PRIORITY_COMPARATOR = (s1, s2) -> {
        Objects.requireNonNull(s1, "s1 is null");
        Objects.requireNonNull(s2, "s2 is null");

        return s1 > s2 ? -1 : (s1 == s2 ? 0 : 1);
    };

    private ConfigurationManagerConfig _config;
    private Map<Integer, ConfigurationSource> _sortedSources;

    private ConcurrentHashMap<Object, DefaultProperty> _properties;
    private Object _propertiesLock;

    private volatile List<Consumer<PropertyChangeEvent>> _changeListeners;

    public DefaultConfigurationManager(ConfigurationManagerConfig config) {
        Objects.requireNonNull(config, "config is null");

        _config = config;

        _sortedSources = new TreeMap<>(PRIORITY_COMPARATOR);
        _sortedSources.putAll(_config.getSources());
        _sortedSources = Collections.unmodifiableMap(_sortedSources);
        _sortedSources.values().forEach(s -> s.addChangeListener(this::onSourceChange));

        _properties = new ConcurrentHashMap<>();
        _propertiesLock = new Object();

        LOGGER.info("Configuration Manager created: {}", toString());
    }

    @Override
    public ConfigurationManagerConfig getConfig() {
        return _config;
    }

    @Override
    public Collection<Property> getProperties() {
        return Collections.unmodifiableCollection(_properties.values());
    }

    protected Map<Integer, ConfigurationSource> getSortedSources() {
        return _sortedSources;
    }

    @Override
    public <K, V> Property<K, V> getProperty(PropertyConfig<K, V> propertyConfig) {
        Objects.requireNonNull(propertyConfig, "propertyConfig is null");

        DefaultProperty<K, V> property = _properties.get(propertyConfig.getKey());
        if (property == null) {
            synchronized (_propertiesLock) {
                property = _properties.get(propertyConfig.getKey());
                if (property == null) {
                    V value = getPropertyValue(propertyConfig);
                    property = newProperty(propertyConfig, value);
                    _properties.put(propertyConfig.getKey(), property);
                }
            }
        }

        if (!Objects.equals(property.getConfig(), propertyConfig))
            throw new IllegalArgumentException(String.format(
                    "make sure using same config for property: %s, previous config: %s, current Config: %s",
                    propertyConfig.getKey(), property.getConfig(), propertyConfig));

        return property;
    }

    @Override
    public <K, V> V getPropertyValue(PropertyConfig<K, V> propertyConfig) {
        Objects.requireNonNull(propertyConfig, "propertyConfig is null");

        for (ConfigurationSource source : _sortedSources.values()) {
            V value = getPropertyValue(source, propertyConfig);

            value = applyValueFilter(propertyConfig, value);

            if (value != null)
                return value;
        }

        return propertyConfig.getDefaultValue();
    }

    protected <K, V> V getPropertyValue(ConfigurationSource source, PropertyConfig<K, V> propertyConfig) {
        V value = null;
        try {
            value = source.getPropertyValue(propertyConfig);
        } catch (Exception e) {
            String message = String.format(
                    "error occurred when getting property value, ignore the source. source: %s, propertyConfig: %s",
                    source, propertyConfig);
            LOGGER.error(message, e);
        }

        return value;
    }

    protected <K, V> V applyValueFilter(PropertyConfig<K, V> propertyConfig, V value) {
        if (value == null)
            return value;

        if (propertyConfig.getValueFilter() == null)
            return value;

        try {
            value = propertyConfig.getValueFilter().apply(value);
        } catch (Exception e) {
            String message = String.format(
                    "failed to run valueFilter, ignore the filter. value: %s, valueFilter: %s, propertyConfig: %s",
                    value, propertyConfig.getValueFilter(), propertyConfig);
            LOGGER.error(message, e);
        }

        return value;
    }

    protected <K, V> DefaultProperty<K, V> newProperty(PropertyConfig<K, V> config, V value) {
        return new DefaultProperty<K, V>(config, value);
    }

    protected void onSourceChange(ConfigurationSource source) {
        synchronized (_propertiesLock) {
            _properties.values().forEach(p -> {
                Object oldValue = p.getValue();
                Object newValue = getPropertyValue(p.getConfig());
                if (Objects.equals(oldValue, newValue))
                    return;
                p.setValue(newValue);

                PropertyChangeEvent event = new DefaultPropertyChangeEvent<>(p, oldValue, newValue);
                _config.getTaskExecutor().accept(() -> p.raiseChangeEvent(event));

                raiseChangeEvent(event);
            });
        }
    }

    @Override
    public synchronized void addChangeListener(Consumer<PropertyChangeEvent> changeListener) {
        Objects.requireNonNull("changeListener", "changeListener is null");

        if (_changeListeners == null)
            _changeListeners = new ArrayList<>();
        _changeListeners.add(changeListener);
    }

    protected synchronized void raiseChangeEvent(PropertyChangeEvent event) {
        if (_changeListeners == null)
            return;

        _changeListeners.forEach(l -> {
            try {
                l.accept(event);
            } catch (Exception e) {
                LOGGER.error("property change listener failed to run", e);
            }
        });
    }

    @Override
    public String toString() {
        return String.format("%s { config: %s, properties: %s, changeListeners: %s }", getClass().getSimpleName(),
                _config, _properties, _changeListeners);
    }

}
