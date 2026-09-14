import React, {useEffect, useMemo, useRef, useState} from 'react';
import {Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions} from 'react-native';
import {SafeAreaView} from 'react-native-safe-area-context';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {useProjectStore} from '../store/ProjectStore';
import {colors, radius, spacing} from '../theme/tokens';
import type {RootStackParamList} from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'Editor'>;

const tools = ['Edit', 'Audio', 'Text', 'Overlay', 'Effects', 'Canvas'];
const trackBlocks = [0.9, 1.2, 0.68, 1.08];

function formatTime(ms: number) {
  const seconds = Math.floor(ms / 1000);
  const tenths = Math.floor((ms % 1000) / 100);
  return `00:${String(seconds).padStart(2, '0')}.${tenths}`;
}

export function EditorScreen({navigation, route}: Props) {
  const {width} = useWindowDimensions();
  const {projects} = useProjectStore();
  const project = projects.find(item => item.id === route.params.projectId);
  const duration = project?.durationMs ?? 10000;
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [selectedTool, setSelectedTool] = useState('Edit');
  const [zoom, setZoom] = useState(1);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!playing) {
      if (timerRef.current) clearInterval(timerRef.current);
      timerRef.current = null;
      return;
    }
    timerRef.current = setInterval(() => {
      setPosition(current => {
        const next = current + 100;
        if (next >= duration) {
          setPlaying(false);
          return 0;
        }
        return next;
      });
    }, 100);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [duration, playing]);

  const previewWidth = Math.min(width - 32, 420);
  const timelineWidth = useMemo(() => Math.max(width * 1.35 * zoom, 520), [width, zoom]);
  const playheadLeft = Math.max(0, Math.min(timelineWidth - 2, (position / duration) * timelineWidth));

  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
      <View style={styles.topBar}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={12} style={styles.iconButton}><Text style={styles.back}>‹</Text></Pressable>
        <View style={styles.titleWrap}>
          <Text numberOfLines={1} style={styles.title}>{project?.title ?? 'Untitled project'}</Text>
          <Text style={styles.saveState}>Saved locally</Text>
        </View>
        <View style={styles.exportDisabled}><Text style={styles.exportText}>Export · later</Text></View>
      </View>

      <View style={styles.workspace}>
        <View style={[styles.previewFrame, {width: previewWidth, height: previewWidth * 0.94}]}>
          <View style={styles.previewCanvas}>
            <View style={styles.previewGlow} />
            <Text style={styles.previewBadge}>9:16</Text>
            <Text style={styles.previewLogo}>VEC</Text>
            <Text style={styles.previewCopy}>Preview surface</Text>
          </View>
        </View>

        <View style={styles.transport}>
          <Text style={styles.time}>{formatTime(position)}</Text>
          <Pressable onPress={() => setPlaying(value => !value)} style={styles.playButton}>
            <Text style={styles.playIcon}>{playing ? 'Ⅱ' : '▶'}</Text>
          </Pressable>
          <Text style={styles.time}>{formatTime(duration)}</Text>
        </View>

        <View style={styles.timelineHeader}>
          <Text style={styles.timelineLabel}>Timeline</Text>
          <View style={styles.zoomGroup}>
            <Pressable onPress={() => setZoom(z => Math.max(0.75, +(z - 0.25).toFixed(2)))} style={styles.zoomButton}><Text style={styles.zoomText}>−</Text></Pressable>
            <Text style={styles.zoomValue}>{Math.round(zoom * 100)}%</Text>
            <Pressable onPress={() => setZoom(z => Math.min(2, +(z + 0.25).toFixed(2)))} style={styles.zoomButton}><Text style={styles.zoomText}>+</Text></Pressable>
          </View>
        </View>

        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.timelineScrollContent}>
          <View style={[styles.timeline, {width: timelineWidth}]}>
            <View style={styles.ruler}>
              {[0, 1, 2, 3, 4, 5].map(mark => <View key={mark} style={styles.rulerTick}><Text style={styles.rulerText}>{mark * 2}s</Text></View>)}
            </View>
            <View style={styles.videoTrack}>
              {trackBlocks.map((factor, index) => (
                <View key={index} style={[styles.clip, {flex: factor}]}>
                  <View style={styles.clipFrame} />
                  <View style={[styles.clipFrame, styles.clipFrameAlt]} />
                  <Text style={styles.clipText}>Clip {index + 1}</Text>
                </View>
              ))}
            </View>
            <View style={styles.audioTrack}>
              {Array.from({length: 38}).map((_, index) => <View key={index} style={[styles.wave, {height: 5 + ((index * 7) % 15)}]} />)}
            </View>
            <View style={[styles.playhead, {left: playheadLeft}]}>
              <View style={styles.playheadCap} />
            </View>
          </View>
        </ScrollView>
      </View>

      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.toolbar}>
        {tools.map(tool => {
          const active = selectedTool === tool;
          return (
            <Pressable key={tool} onPress={() => setSelectedTool(tool)} style={[styles.tool, active && styles.toolActive]}>
              <View style={[styles.toolDot, active && styles.toolDotActive]} />
              <Text style={[styles.toolText, active && styles.toolTextActive]}>{tool}</Text>
            </Pressable>
          );
        })}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: {flex: 1, backgroundColor: colors.background},
  topBar: {height: 58, flexDirection: 'row', alignItems: 'center', paddingHorizontal: spacing.md, borderBottomWidth: 1, borderBottomColor: colors.border},
  iconButton: {width: 40, height: 40, justifyContent: 'center'},
  back: {color: colors.text, fontSize: 36, lineHeight: 36},
  titleWrap: {flex: 1, alignItems: 'center'},
  title: {color: colors.text, fontSize: 14, fontWeight: '800', maxWidth: 180},
  saveState: {color: colors.textFaint, fontSize: 10, marginTop: 2},
  exportDisabled: {paddingHorizontal: 11, paddingVertical: 8, borderRadius: radius.sm, backgroundColor: colors.surfaceRaised, opacity: 0.72},
  exportText: {color: colors.textMuted, fontSize: 10, fontWeight: '800'},
  workspace: {flex: 1, alignItems: 'center', paddingTop: spacing.md},
  previewFrame: {maxHeight: 355, borderRadius: radius.md, backgroundColor: colors.black, padding: 8, justifyContent: 'center', alignItems: 'center'},
  previewCanvas: {height: '100%', aspectRatio: 9 / 16, maxWidth: '100%', overflow: 'hidden', justifyContent: 'center', alignItems: 'center', backgroundColor: '#161328', borderRadius: 8},
  previewGlow: {position: 'absolute', width: 210, height: 210, borderRadius: 105, backgroundColor: colors.accent, opacity: 0.35, top: -80, right: -95},
  previewBadge: {position: 'absolute', top: 10, left: 10, color: colors.textMuted, fontSize: 10, fontWeight: '700'},
  previewLogo: {color: colors.text, fontSize: 42, fontWeight: '900', letterSpacing: 3},
  previewCopy: {color: colors.textMuted, fontSize: 10, marginTop: 5},
  transport: {height: 52, width: '100%', flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.xl},
  time: {color: colors.textMuted, fontSize: 10, minWidth: 52, textAlign: 'center'},
  playButton: {width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.surfaceRaised},
  playIcon: {color: colors.text, fontSize: 13, fontWeight: '900'},
  timelineHeader: {width: '100%', paddingHorizontal: spacing.md, height: 38, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderTopWidth: 1, borderTopColor: colors.border},
  timelineLabel: {color: colors.text, fontSize: 11, fontWeight: '800'},
  zoomGroup: {flexDirection: 'row', alignItems: 'center', gap: 7},
  zoomButton: {width: 26, height: 26, borderRadius: 8, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.surfaceRaised},
  zoomText: {color: colors.text, fontSize: 16, fontWeight: '700'},
  zoomValue: {color: colors.textMuted, fontSize: 9, minWidth: 34, textAlign: 'center'},
  timelineScrollContent: {paddingHorizontal: spacing.md, paddingBottom: spacing.md},
  timeline: {height: 128, position: 'relative'},
  ruler: {height: 24, flexDirection: 'row', justifyContent: 'space-between'},
  rulerTick: {width: 24},
  rulerText: {color: colors.textFaint, fontSize: 8},
  videoTrack: {height: 55, flexDirection: 'row', gap: 3},
  clip: {minWidth: 52, overflow: 'hidden', borderRadius: 7, backgroundColor: '#383055', flexDirection: 'row', alignItems: 'stretch'},
  clipFrame: {flex: 1, backgroundColor: '#4B3F73'},
  clipFrameAlt: {backgroundColor: '#2E5A63'},
  clipText: {position: 'absolute', bottom: 4, left: 5, color: colors.white, fontSize: 8, fontWeight: '800'},
  audioTrack: {marginTop: 6, height: 31, borderRadius: 6, paddingHorizontal: 5, flexDirection: 'row', alignItems: 'center', gap: 2, backgroundColor: '#173D35'},
  wave: {width: 2, borderRadius: 2, backgroundColor: colors.success, opacity: 0.8},
  playhead: {position: 'absolute', top: 16, bottom: 0, width: 2, backgroundColor: colors.white, zIndex: 10},
  playheadCap: {position: 'absolute', top: -1, left: -4, width: 10, height: 10, borderRadius: 5, backgroundColor: colors.white},
  toolbar: {minHeight: 72, paddingHorizontal: spacing.md, paddingVertical: spacing.sm, gap: spacing.sm, borderTopWidth: 1, borderTopColor: colors.border, backgroundColor: colors.surface},
  tool: {width: 68, height: 54, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', gap: 5},
  toolActive: {backgroundColor: colors.accentSoft},
  toolDot: {width: 18, height: 18, borderRadius: 6, backgroundColor: colors.surfaceSoft, borderWidth: 1, borderColor: colors.border},
  toolDotActive: {backgroundColor: colors.accent, borderColor: colors.accent},
  toolText: {color: colors.textMuted, fontSize: 10, fontWeight: '700'},
  toolTextActive: {color: colors.text},
});
