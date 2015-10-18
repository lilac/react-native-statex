
A React Native state storage for Android.

### Installation

```bash
npm install --save react-native-statex
```

### Add it to your android project

* In `android/setting.gradle`

```gradle
...
include ':statex', ':app'
project(':statex').projectDir = new File(rootProject.projectDir, '../node_modules/react-native-statex')
```

* In `android/app/build.gradle`

```gradle
...
dependencies {
    ...
    compile project(':statex')
}
```

* register module (in MainActivity.java)

```java
import co.rewen.statex.StateXPackage;;  // <--- import

public class MainActivity extends Activity implements DefaultHardwareBackBtnHandler {
  ......

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mReactRootView = new ReactRootView(this);

    mReactInstanceManager = ReactInstanceManager.builder()
      .setApplication(getApplication())
      .setBundleAssetName("index.android.bundle")
      .setJSMainModuleName("index.android")
      .addPackage(new MainReactPackage())
      .addPackage(new StateXPackage())              // <------ add here
      .setUseDeveloperSupport(BuildConfig.DEBUG)
      .setInitialLifecycleState(LifecycleState.RESUMED)
      .build();

    mReactRootView.startReactApplication(mReactInstanceManager, "Example", null);

    setContentView(mReactRootView);
  }

  ......

}
```

## Example
```javascript
var StateX = require('react-native-statex');

var key = "KEY";
var value = StateX.getItem(key);
StateX.setItem(key, "Hi");
```
## License

MIT
