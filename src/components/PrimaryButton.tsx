import React from 'react';
import {Pressable, StyleSheet, Text, View} from 'react-native';
import {colors, radius, spacing} from '../theme/tokens';

type Props = {
  label: string;
  hint?: string;
  onPress: () => void;
};

export function PrimaryButton({label, hint, onPress}: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={({pressed}) => [styles.button, pressed && styles.pressed]}>
      <View style={styles.plusBadge}>
        <Text style={styles.plus}>+</Text>
      </View>
      <View style={styles.copy}>
        <Text style={styles.label}>{label}</Text>
        {hint ? <Text style={styles.hint}>{hint}</Text> : null}
      </View>
      <Text style={styles.arrow}>›</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 76,
    borderRadius: radius.lg,
    paddingHorizontal: spacing.lg,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.accent,
  },
  pressed: {opacity: 0.86, transform: [{scale: 0.995}]},
  plusBadge: {
    width: 42,
    height: 42,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.16)',
  },
  plus: {fontSize: 27, color: colors.white, marginTop: -2},
  copy: {flex: 1, marginLeft: spacing.md},
  label: {color: colors.white, fontSize: 17, fontWeight: '800'},
  hint: {color: 'rgba(255,255,255,0.75)', fontSize: 12, marginTop: 3},
  arrow: {color: colors.white, fontSize: 28, marginLeft: spacing.sm},
});
