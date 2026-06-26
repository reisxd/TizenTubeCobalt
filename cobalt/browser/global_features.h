// Copyright 2025 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef COBALT_BROWSER_GLOBAL_FEATURES_H_
#define COBALT_BROWSER_GLOBAL_FEATURES_H_

#include <optional>

#include "base/feature_list.h"
#include "base/memory/raw_ptr.h"
#include "base/no_destructor.h"
#include "base/sequence_checker.h"
#include "base/synchronization/lock.h"
#include "cobalt/browser/constants/cobalt_experiment_names.h"
#include "cobalt/browser/experiments/experiment_config_manager.h"
#include "components/prefs/pref_registry_simple.h"
#include "third_party/abseil-cpp/absl/container/flat_hash_map.h"

class PrefService;

namespace metrics {
class MetricsService;
}  // namespace metrics

namespace metrics_services_manager {
class MetricsServicesManager;
}  // namespace metrics_services_manager

namespace cobalt {
class CobaltMetricsServicesManagerClient;

// This class owns features that are globally scoped. It follows the structure
// of BrowserProcess class in Chrome. This class is a singleton and can only be
// accessed on a single SequenceTaskRunner.
// TODO(b/407734389): Add unittests.
class GlobalFeatures {
 public:
  ~GlobalFeatures() = default;

  static GlobalFeatures* GetInstance();

  // Registers experiment config prefs used by this class.
  static void RegisterPrefs(PrefRegistrySimple* registry);

  base::FeatureList::Accessor* accessor() { return accessor_.get(); }
  metrics_services_manager::MetricsServicesManager* metrics_services_manager();
  metrics::MetricsService* metrics_service();
  CobaltMetricsServicesManagerClient* metrics_services_manager_client();
  PrefService* experiment_config();
  PrefService* metrics_local_state();
  PrefService* local_state();
  const std::string& active_config_data() { return active_config_data_; }
  const std::string& latest_config_hash() {
    return experiment_config_->GetString(kLatestConfigHash);
  }
  ExperimentConfigManager* experiment_config_manager() {
    return experiment_config_manager_.get();
  }

  void set_accessor(std::unique_ptr<base::FeatureList::Accessor> accessor);

  // Explicitly shuts down the metrics service. This is to ensure the
  // CobaltMetricsServiceClient destructor is called, which logs a clean
  // shutdown. The specific shutdown order here is required to nullify
  // a raw pointer and prevent a use-after-free crash
  // that would otherwise occur on exit.
  void Shutdown();

  using SettingValue = std::variant<std::string, int64_t>;

  const absl::flat_hash_map<std::string, SettingValue>& GetSettings() const;
  void SetSettings(const std::string& key, const SettingValue& value);

  static constexpr char kUserAgentOverridePrefName[] = "user_agent_override";

 private:
  friend class base::NoDestructor<GlobalFeatures>;

  GlobalFeatures();

  // Initialize a PrefService instance for the experiment config.
  void CreateExperimentConfig();
  // Initialize CobaltMetricsServicesManagerClient instance and use it to
  // initialize MetricsServicesManager.
  void CreateMetricsServices();
  // Initialize a PrefService instance for local state for Metrics services.
  void CreateMetricsLocalState();
  // Restore persisted settings from the pref store into memory.
  void RestorePersistedSettings();
  // Initialize a PrefService instance for local state.
  void CreateLocalState();
  // Record the active config data in the member variable to preserve the active
  // config data for the current app life cycle in case it's needed after the
  // config data in storage has been modified.
  // Modified config data should only apply to the next app life cycle.
  void InitializeActiveConfigData();

  // Construct a FilePath for a pref file and ensure its parent directory
  // exists.
  base::FilePath GetPrefFilePath(const base::FilePath::CharType filename[],
                                 const char* label);

  std::unique_ptr<base::FeatureList::Accessor> accessor_;

  // Finch config/state.
  std::unique_ptr<PrefService> experiment_config_;

  // UMA config/state.
  std::unique_ptr<PrefService> metrics_local_state_;

  // |metrics_services_manager_| owns this.
  raw_ptr<CobaltMetricsServicesManagerClient, DanglingUntriaged>
      metrics_services_manager_client_;

  // Must be destroyed before |metrics_local_state_|.
  std::unique_ptr<metrics_services_manager::MetricsServicesManager>
      metrics_services_manager_;

  std::unique_ptr<ExperimentConfigManager> experiment_config_manager_;

  std::string active_config_data_;

  mutable base::Lock lock_;
  absl::flat_hash_map<std::string, SettingValue> settings_ GUARDED_BY(lock_);

  SEQUENCE_CHECKER(sequence_checker_);
};

}  // namespace cobalt

#endif  // COBALT_BROWSER_GLOBAL_FEATURES_H_
