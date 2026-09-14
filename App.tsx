import React from 'react';
import {StatusBar} from 'react-native';
import {NavigationContainer} from '@react-navigation/native';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {RootNavigator} from './src/navigation/RootNavigator';
import {ProjectStoreProvider} from './src/store/ProjectStore';
import {colors} from './src/theme/tokens';

export default function App() {
  return (
    <SafeAreaProvider>
      <ProjectStoreProvider>
        <StatusBar barStyle="light-content" />
        <NavigationContainer
          theme={{
            dark: true,
            colors: {
              primary: colors.accent,
              background: colors.background,
              card: colors.surface,
              text: colors.text,
              border: colors.border,
              notification: colors.accent,
            },
            fonts: {
              regular: {fontFamily: 'sans-serif', fontWeight: '400'},
              medium: {fontFamily: 'sans-serif-medium', fontWeight: '500'},
              bold: {fontFamily: 'sans-serif', fontWeight: '700'},
              heavy: {fontFamily: 'sans-serif', fontWeight: '800'},
            },
          }}>
          <RootNavigator />
        </NavigationContainer>
      </ProjectStoreProvider>
    </SafeAreaProvider>
  );
}
