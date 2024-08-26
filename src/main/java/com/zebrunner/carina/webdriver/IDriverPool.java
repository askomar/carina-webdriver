/*******************************************************************************
 * Copyright 2020-2022 Zebrunner Inc (https://www.zebrunner.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.zebrunner.carina.webdriver;

import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.appium.java_client.remote.MobileCapabilityType;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.support.decorators.Decorated;
import org.openqa.selenium.support.ui.FluentWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zebrunner.carina.utils.R;
import com.zebrunner.carina.utils.common.CommonUtils;
import com.zebrunner.carina.utils.commons.SpecialKeywords;
import com.zebrunner.carina.utils.config.Configuration;
import com.zebrunner.carina.utils.exception.DriverPoolException;
import com.zebrunner.carina.webdriver.config.WebDriverConfiguration;
import com.zebrunner.carina.webdriver.config.WebDriverConfiguration.Parameter;
import com.zebrunner.carina.webdriver.core.factory.DriverFactory;
import com.zebrunner.carina.webdriver.device.Device;

import javax.annotation.Nullable;

public interface IDriverPool {

    Logger POOL_LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    String DEFAULT = "default";
    // unified set of Carina WebDrivers
    ConcurrentHashMap<CarinaDriver, Integer> driversMap = new ConcurrentHashMap<>();
    @SuppressWarnings("static-access")
    Set<CarinaDriver> driversPool = driversMap.newKeySet();
    ThreadLocal<Device> currentDevice = new ThreadLocal<>();
    Device nullDevice = new Device();
    ThreadLocal<MutableCapabilities> customCapabilities = new ThreadLocal<>();

    /**
     * Get default driver. If no default driver discovered it will be created.
     *
     * @return default WebDriver
     */
    default WebDriver getDriver() {
        return getDriver(DEFAULT);
    }

    /**
     * Get driver by name. If no driver discovered it will be created using
     * default capabilities.
     *
     * @param name String driver name
     * @return WebDriver
     */
    default WebDriver getDriver(String name) {
        //customCapabilities.get() return registered custom capabilities or null as earlier
        return getDriver(name, customCapabilities.get(), null);
    }

    /**
     * Get driver by name and Capabilities.
     *
     * @param name         String driver name
     * @param capabilities capabilities
     * @return WebDriver
     */
    default WebDriver getDriver(String name, MutableCapabilities capabilities) {
        return getDriver(name, capabilities, null);
    }

    /**
     * Get driver by name. If no driver discovered it will be created using
     * custom capabilities and selenium server.
     *
     * @param name         String driver name
     * @param capabilities capabilities
     * @param seleniumHost String
     * @return WebDriver
     */
    default WebDriver getDriver(String name, MutableCapabilities capabilities, String seleniumHost) {
        WebDriver drv = null;
        ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
        if (currentDrivers.containsKey(name)) {
            CarinaDriver cdrv = currentDrivers.get(name);
            drv = cdrv.getDriver();
            if (TestPhase.Phase.BEFORE_SUITE.equals(cdrv.getPhase())) {
                POOL_LOGGER.info("Before suite registered driver will be returned.");
            } else {
                POOL_LOGGER.debug("{} registered driver will be returned.", cdrv.getPhase());
            }
        }
        if (drv == null) {
            POOL_LOGGER.debug("Starting new driver as nothing was found in the pool");
            drv = createDriver(name, capabilities, seleniumHost);
        }
        // [VD] do not wrap EventFiringWebDriver here otherwise DriverListener and all logging will be lost!
        return drv;
    }

    /**
     * Get driver by sessionId.
     *
     * @param sessionId session id to be used for searching a desired driver
     * @return default WebDriver
     */
    public static WebDriver getDriver(SessionId sessionId) {
        for (CarinaDriver carinaDriver : driversPool) {
            WebDriver drv = carinaDriver.getDriver();
            SessionId drvSessionId;
            if (drv instanceof Decorated<?>) {
                drvSessionId = ((RemoteWebDriver) (((Decorated<?>) drv).getOriginal())).getSessionId();
            } else {
                drvSessionId = ((RemoteWebDriver) drv).getSessionId();
            }
            if (sessionId.equals(drvSessionId)) {
                return drv;
            }
        }
        throw new DriverPoolException("Unable to find driver using sessionId artifacts. Returning default one!");
    }

    /**
     * Get driver registered to device. If no device discovered null will be returned.
     *
     * @param device Device
     * @return WebDriver
     */
    default WebDriver getDriver(Device device) {
        WebDriver drv = null;
        for (CarinaDriver carinaDriver : driversPool) {
            if (carinaDriver.getDevice().equals(device)) {
                drv = carinaDriver.getDriver();
            }
        }
        return drv;
    }

    /**
     * Restart default driver
     *
     * @return WebDriver
     */
    default WebDriver restartDriver() {
        return restartDriver(false);
    }

    /**
     * Restart default driver on the same device
     *
     * @param isSameDevice boolean restart driver on the same device or not
     * @return WebDriver
     */
    default WebDriver restartDriver(boolean isSameDevice) {
       return restartDriver(isSameDevice, null);
    }

    default WebDriver restartDriver(boolean isSameDevice, @Nullable Capabilities additionalOptions) {
        WebDriver drv = getDriver(DEFAULT);
        Device device = nullDevice;
        MutableCapabilities udidCaps = new MutableCapabilities();
        boolean keepProxy = false;
        if (isSameDevice) {
            keepProxy = true;
            device = getDevice(drv);
            POOL_LOGGER.debug("Added udid: {} to capabilities for restartDriver on the same device.", device.getUdid());
            udidCaps.setCapability(MobileCapabilityType.UDID, device.getUdid());
        }
        udidCaps = udidCaps.merge(additionalOptions);

        Capabilities capabilities = null;
        POOL_LOGGER.debug("before restartDriver: {}", driversPool);
        for (CarinaDriver carinaDriver : driversPool) {
            if (carinaDriver.getDriver().equals(drv)) {
                capabilities = carinaDriver.getOriginalCapabilities()
                        .merge(udidCaps);
                quitDriver(carinaDriver, keepProxy);
                // [VD] don't remove break or refactor moving removal out of "for" cycle
                driversPool.remove(carinaDriver);
                break;
            }
        }
        POOL_LOGGER.debug("after restartDriver: {}", driversPool);
        return createDriver(DEFAULT, capabilities, null);
    }

    /**
     * Quit default driver
     */
    default void quitDriver() {
        quitDriver(DEFAULT);
    }

    /**
     * Quit driver by name
     *
     * @param name String driver name
     */
    default void quitDriver(String name) {
        WebDriver drv = null;
        CarinaDriver carinaDrv = null;
        Long threadId = Thread.currentThread().getId();

        POOL_LOGGER.debug("before quitDriver: {}", driversPool);
        for (CarinaDriver carinaDriver : driversPool) {
            if ((TestPhase.Phase.BEFORE_SUITE.equals(carinaDriver.getPhase()) && name.equals(carinaDriver.getName()))
                    || (threadId.equals(carinaDriver.getThreadId()) && name.equals(carinaDriver.getName()))) {
                drv = carinaDriver.getDriver();
                carinaDrv = carinaDriver;
                break;
            }
        }

        if (drv == null) {
            throw new RuntimeException(String.format("Unable to find driver '%s'!", name));
        }
        quitDriver(carinaDrv, false);
        driversPool.remove(carinaDrv);
        POOL_LOGGER.debug("after quitDriver: {}", driversPool);

    }

    /**
     * Quit current drivers by phase(s). "Current" means assigned to the current test/thread.
     *
     * @param phase Comma separated driver phases to quit
     */
    default void quitDrivers(TestPhase.Phase... phase) {
        List<TestPhase.Phase> phases = Arrays.asList(phase);
        Set<CarinaDriver> drivers4Remove = new HashSet<>();
        Long threadId = Thread.currentThread().getId();
        for (CarinaDriver carinaDriver : driversPool) {
            if ((phases.contains(carinaDriver.getPhase()) && threadId.equals(carinaDriver.getThreadId()))
                    || phases.contains(TestPhase.Phase.ALL)) {
                quitDriver(carinaDriver, false);
                drivers4Remove.add(carinaDriver);
            }
        }
        driversPool.removeAll(drivers4Remove);
        removeCapabilities();
    }

    /**
     * Set custom capabilities.
     *
     * @param caps capabilities
     */
    default void setCapabilities(MutableCapabilities caps) {
        customCapabilities.set(caps);
    }

    /**
     * Remove custom capabilities.
     */
    default void removeCapabilities() {
        customCapabilities.remove();
    }

    private void quitDriver(CarinaDriver carinaDriver, @Deprecated boolean keepProxyDuring) {
        try {
            carinaDriver.getDevice().disconnectRemote();
            // castDriver to disable DriverListener operations on quit
            POOL_LOGGER.debug("start driver quit: {}", carinaDriver.getName());
            // default timeout for driver quit 1/2 of explicit
            Duration timeout = Duration.ofSeconds(Configuration.getRequired(Parameter.DRIVER_CLOSE_TIMEOUT, Integer.class));
            try {
                new FluentWait<>(castDriver(carinaDriver.getDriver()))
                        .pollingEvery(timeout.plus(Duration.ofSeconds(5)))
                        .withTimeout(timeout)
                        .until(driver -> {
                            if (Configuration.get(WebDriverConfiguration.Parameter.CHROME_CLOSURE, Boolean.class).orElse(false)) {
                                // workaround to not cleaned chrome profiles on hard drive
                                POOL_LOGGER.debug("Starting drv.close()");
                                driver.close();
                                POOL_LOGGER.debug("Finished drv.close()");
                            }
                            POOL_LOGGER.debug("Starting drv.quit()");
                            driver.quit();
                            POOL_LOGGER.debug("Finished drv.quit()");
                            return true;
                        });
            } catch (org.openqa.selenium.TimeoutException e) {
                POOL_LOGGER.error("Unable to quit driver for {} sec!", timeout, e);
            }
        } catch (WebDriverException e) {
            POOL_LOGGER.debug("Error message detected during driver quit!", e);
            // do nothing
        } catch (Exception e) {
            POOL_LOGGER.error("Error discovered during driver quit!", e);
        } finally {
            POOL_LOGGER.debug("finished driver quit: {}", carinaDriver.getName());
        }
    }

    private WebDriver castDriver(WebDriver drv) {
        if (drv instanceof Decorated<?>) {
            drv = (WebDriver) ((Decorated<?>) drv).getOriginal();
        }
        return drv;
    }

    /**
     * Create driver with custom capabilities
     *
     * @param name         String driver name
     * @param capabilities capabilities
     * @param seleniumHost String
     * @return {@link ImmutablePair} with {@link WebDriver} and original {@link Capabilities}
     */
    private WebDriver createDriver(String name, Capabilities capabilities, String seleniumHost) {
        int count = 0;
        WebDriver drv = null;
        Device device = nullDevice;

        // 1 - is default run without retry
        int maxCount = Configuration.getRequired(Parameter.INIT_RETRY_COUNT, Integer.class) + 1;
        while (drv == null && count++ < maxCount) {
            try {
                POOL_LOGGER.debug("initDriver start...");
                long threadId = Thread.currentThread().getId();
                ConcurrentHashMap<String, CarinaDriver> currentDrivers = getDrivers();
                int maxDriverCount = Configuration.getRequired(Parameter.MAX_DRIVER_COUNT, Integer.class);
                if (currentDrivers.size() == maxDriverCount) {
                    throw new RuntimeException(String.format("Unable to create new driver as you reached max number of drivers per thread: %s !" +
                            " Override max_driver_count to allow more drivers per test!", maxDriverCount));
                }
                // [VD] pay attention that similar piece of code is copied into the DriverPoolTest as registerDriver method!
                if (currentDrivers.containsKey(name)) {
                    // [VD] moved containsKey verification before the driver start
                    throw new RuntimeException(String.format("Driver '%s' is already registered for thread: %s", name, threadId));
                }
                ImmutablePair<WebDriver, Capabilities> pair = DriverFactory.create(name, capabilities, seleniumHost);
                drv = pair.getLeft();

                if (currentDevice.get() != null) {
                    device = currentDevice.get();
                }

                CarinaDriver carinaDriver = new CarinaDriver(name, drv, device, TestPhase.getActivePhase(), threadId, pair.getRight());
                driversPool.add(carinaDriver);
                POOL_LOGGER.debug("initDriver finish...");
            } catch (Exception e) {
                device.disconnectRemote();
                //TODO: [VD] think about excluding device from pool for explicit reasons like out of space etc
                // but initially try to implement it on selenium-hub level
                String msg = String.format("Driver initialization '%s' FAILED! Retry %d of %d time - %s", name, count,
                        maxCount, e.getMessage());
                if (count == maxCount) {
                    throw e;
                } else {
                    // do not provide huge stacktrace as more retries exists. Only latest will generate full error + stacktrace
                    POOL_LOGGER.error(msg);
                }
                CommonUtils.pause(Configuration.getRequired(Parameter.INIT_RETRY_INTERVAL, Integer.class));
            }
        }

        if (drv == null) {
            throw new RuntimeException("Undefined exception detected! Analyze above logs for details.");
        }
        return drv;
    }

    /**
     * Verify if driver is registered in the DriverPool
     *
     * @param name String driver name
     * @return boolean
     */
    default boolean isDriverRegistered(String name) {
        return getDrivers().containsKey(name);
    }

    /**
     * Return all drivers registered in the DriverPool for this thread including
     * on Before Suite/Class/Method stages
     *
     * @return ConcurrentHashMap of driver names and Carina WebDrivers
     */
    default ConcurrentHashMap<String, CarinaDriver> getDrivers() {
        Long threadId = Thread.currentThread().getId();
        ConcurrentHashMap<String, CarinaDriver> currentDrivers = new ConcurrentHashMap<>();
        for (CarinaDriver carinaDriver : driversPool) {
            if (TestPhase.Phase.BEFORE_SUITE.equals(carinaDriver.getPhase()) ||
                    threadId.equals(carinaDriver.getThreadId())) {
                currentDrivers.put(carinaDriver.getName(), carinaDriver);
            }
        }
        return currentDrivers;
    }

    // ------------------------ DEVICE POOL METHODS -----------------------

    /**
     * Get device registered to default driver. If no default driver discovered nullDevice will be returned.
     *
     * @return default Device
     */
    default Device getDevice() {
        return getDevice(DEFAULT);
    }

    /**
     * Get device registered to named driver. If no driver discovered nullDevice will be returned.
     *
     * @param name String driver name
     * @return Device
     */
    default Device getDevice(String name) {
        if (isDriverRegistered(name)) {
            return getDrivers().get(name).getDevice();
        } else {
            return nullDevice;
        }

    }

    /**
     * Get device registered to driver. If no driver discovered nullDevice will be returned.
     *
     * @param drv WebDriver
     * @return Device
     */
    default Device getDevice(WebDriver drv) {
        Device device = nullDevice;

        for (CarinaDriver carinaDriver : driversPool) {
            if (carinaDriver.getDriver().equals(drv)) {
                device = carinaDriver.getDevice();
                break;
            }
        }

        return device;
    }

    /**
     * Register device information for current thread by MobileFactory and clear SysLog for Android only
     *
     * @param device String Device device
     * @return Device device
     */
    static Device registerDevice(Device device) {
        // register current device to be able to transfer it into Zafira at the end of the test
        long threadId = Thread.currentThread().getId();
        POOL_LOGGER.debug("Set current device '{}' to thread: {}", device.getName(), threadId);
        currentDevice.set(device);
        POOL_LOGGER.debug("register device for current thread id: {}; device: '{}'", threadId, device.getName());
        boolean enableAdb = R.CONFIG.getBoolean(SpecialKeywords.ENABLE_ADB);
        if (enableAdb) {
            device.connectRemote();
        }
        return device;
    }

    /**
     * Return last registered device information for current thread.
     *
     * @return Device device
     */
    @Deprecated
    static Device getDefaultDevice() {
        long threadId = Thread.currentThread().getId();
        Device device = currentDevice.get();
        if (device == null) {
            device = nullDevice;
        } else if (device.getName().isEmpty()) {
            POOL_LOGGER.debug("Current device name is empty! nullDevice was used for thread: {}", threadId);
        } else {
            POOL_LOGGER.debug("Current device name is '{}' for thread: {}", device.getName(), threadId);
        }
        return device;
    }

    /**
     * Return nullDevice object to avoid NullPointerException and tons of verification across carina-core modules.
     *
     * @return Device device
     */
    static Device getNullDevice() {
        return nullDevice;
    }

    /**
     * Verify if device is registered in the Pool
     *
     * @return boolean
     */
    default boolean isDeviceRegistered() {
        Device device = currentDevice.get();
        return device != null && device != nullDevice;
    }
}
