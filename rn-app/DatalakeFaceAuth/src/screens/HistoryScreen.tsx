import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, FlatList, ActivityIndicator, RefreshControl } from 'react-native';
import FaceAuthService, { AttendanceLog } from '../services/FaceAuthService';

export const HistoryScreen = () => {
  const [logs, setLogs] = useState<AttendanceLog[]>([]);
  const [lastSynced, setLastSynced] = useState<string>('Never');
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);

  const fetchData = async () => {
    try {
      const fetchedLogs = await FaceAuthService.getAttendanceLog();
      const syncTime = await FaceAuthService.getLastSyncTime();
      
      setLogs(fetchedLogs || []);
      setLastSynced(syncTime || 'Never');
    } catch (error) {
      console.error('Error fetching history:', error);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const onRefresh = () => {
    setRefreshing(true);
    fetchData();
  };

  const renderItem = ({ item }: { item: AttendanceLog }) => (
    <View style={styles.logCard}>
      <View style={styles.logHeader}>
        <Text style={styles.logName}>{item.name}</Text>
        <View style={[styles.badge, item.synced ? styles.badgeSynced : styles.badgePending]}>
          <Text style={styles.badgeText}>{item.synced ? 'Synced' : 'Pending'}</Text>
        </View>
      </View>
      
      <View style={styles.logDetails}>
        <Text style={styles.logTime}>{new Date(item.timestamp).toLocaleString()}</Text>
        <Text style={styles.logScore}>Score: {(item.score * 100).toFixed(1)}%</Text>
      </View>
    </View>
  );

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Attendance History</Text>
        <Text style={styles.syncStatus}>Last Synced: {lastSynced !== 'Never' ? new Date(lastSynced).toLocaleString() : 'Never'}</Text>
      </View>

      {loading ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" color="#2196f3" />
        </View>
      ) : (
        <FlatList
          data={logs}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          contentContainerStyle={styles.listContainer}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor="#2196f3" />
          }
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text style={styles.emptyText}>No attendance records found.</Text>
            </View>
          }
        />
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
  },
  header: {
    padding: 20,
    paddingTop: 60,
    backgroundColor: '#1e1e1e',
    borderBottomWidth: 1,
    borderBottomColor: '#333',
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#fff',
    marginBottom: 8,
  },
  syncStatus: {
    fontSize: 14,
    color: '#aaa',
  },
  listContainer: {
    padding: 16,
  },
  logCard: {
    backgroundColor: '#1e1e1e',
    padding: 16,
    borderRadius: 12,
    marginBottom: 12,
  },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  logName: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#fff',
  },
  badge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  badgeSynced: {
    backgroundColor: 'rgba(76, 175, 80, 0.2)',
  },
  badgePending: {
    backgroundColor: 'rgba(255, 152, 0, 0.2)',
  },
  badgeText: {
    fontSize: 12,
    fontWeight: 'bold',
    color: '#fff',
  },
  logDetails: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  logTime: {
    fontSize: 14,
    color: '#aaa',
  },
  logScore: {
    fontSize: 14,
    color: '#2196f3',
    fontWeight: '600',
  },
  center: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  emptyContainer: {
    padding: 40,
    alignItems: 'center',
  },
  emptyText: {
    color: '#666',
    fontSize: 16,
  },
});

export default HistoryScreen;
