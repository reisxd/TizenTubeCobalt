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

#include "cobalt/browser/global_features.h"

#include <variant>

#include "base/feature_list.h"
#include "base/files/file_util.h"
#include "base/json/string_escape.h"
#include "base/no_destructor.h"
#include "base/path_service.h"
#include "base/time/time.h"
#include "cobalt/browser/constants/cobalt_experiment_names.h"
#include "cobalt/browser/metrics/cobalt_metrics_services_manager_client.h"
#include "components/metrics/metrics_pref_names.h"
#include "components/metrics/metrics_service.h"
#include "components/metrics_services_manager/metrics_services_manager.h"
#include "components/prefs/json_pref_store.h"
#include "components/prefs/pref_registry.h"
#include "components/prefs/pref_service.h"
#include "components/prefs/pref_service_factory.h"
#include "components/variations/pref_names.h"
#include "third_party/abseil-cpp/absl/container/flat_hash_map.h"

namespace cobalt {

constexpr base::FilePath::CharType kExperimentConfigFilename[] =
    FILE_PATH_LITERAL("Experiment Config");

constexpr base::FilePath::CharType kMetricsConfigFilename[] =
    FILE_PATH_LITERAL("Metrics Config");

GlobalFeatures::GlobalFeatures() {
  CreateExperimentConfig();
  CreateMetricsServices();
  RestorePersistedSettings();
  // InitializeActiveConfigData needs ExperimentConfigManager to determine
  // the experiment config type.
  experiment_config_manager_ = std::make_unique<ExperimentConfigManager>(
      experiment_config_.get(), metrics_local_state_.get());
  InitializeActiveConfigData();
}

// static
GlobalFeatures* GlobalFeatures::GetInstance() {
  static base::NoDestructor<GlobalFeatures> instance;
  return instance.get();
}

metrics_services_manager::MetricsServicesManager*
GlobalFeatures::metrics_services_manager() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  // TODO(b/407734389): Add testing for DCHECK
  DCHECK(metrics_services_manager_);
  return metrics_services_manager_.get();
}

metrics::MetricsService* GlobalFeatures::metrics_service() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return metrics_services_manager()->GetMetricsService();
}

CobaltMetricsServicesManagerClient*
GlobalFeatures::metrics_services_manager_client() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return metrics_services_manager_client_.get();
}

PrefService* GlobalFeatures::experiment_config() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return experiment_config_.get();
}

PrefService* GlobalFeatures::metrics_local_state() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return metrics_local_state_.get();
}

PrefService* GlobalFeatures::local_state() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return metrics_local_state_.get();
}

void GlobalFeatures::set_accessor(
    std::unique_ptr<base::FeatureList::Accessor> accessor) {
  accessor_ = std::move(accessor);
}

const absl::flat_hash_map<std::string, GlobalFeatures::SettingValue>&
GlobalFeatures::GetSettings() const {
  base::AutoLock auto_lock(lock_);
  return settings_;
}

void GlobalFeatures::SetSettings(const std::string& key,
                                 const SettingValue& value) {
  base::AutoLock auto_lock(lock_);
  settings_[key] = value;

  if (metrics_local_state_ && key == kUserAgentOverridePrefName) {
    if (const auto* user_agent = std::get_if<std::string>(&value)) {
      metrics_local_state_->SetString(kUserAgentOverridePrefName, *user_agent);
    }
  }

  LOG(INFO) << "SetSettings: key=" << key << ", value=" << [&value] {
    if (const auto* s = std::get_if<std::string>(&value)) {
      return base::GetQuotedJSONString(*s);
    } else if (const auto* i = std::get_if<int64_t>(&value)) {
      return std::to_string(*i);
    }
    NOTREACHED();
  }();
}

void GlobalFeatures::CreateExperimentConfig() {
  DCHECK(!experiment_config_);
  auto pref_registry = base::MakeRefCounted<PrefRegistrySimple>();

  RegisterPrefs(pref_registry.get());

  base::FilePath path =
      GetPrefFilePath(kExperimentConfigFilename, "experiment config");

  PrefServiceFactory pref_service_factory;
  pref_service_factory.set_user_prefs(
      base::MakeRefCounted<JsonPrefStore>(path));

  experiment_config_ = pref_service_factory.Create(pref_registry);
}

void GlobalFeatures::CreateMetricsServices() {
  CreateMetricsLocalState();
  DCHECK(metrics_local_state_)
      << "CreateMetricsLocalState() must have been called previously";
  auto client = std::make_unique<CobaltMetricsServicesManagerClient>(
      metrics_local_state_.get());
  metrics_services_manager_client_ = client.get();
  metrics_services_manager_ =
      std::make_unique<metrics_services_manager::MetricsServicesManager>(
          std::move(client));
}

void GlobalFeatures::CreateMetricsLocalState() {
  DCHECK(!metrics_local_state_);
  // No need to make `pref_registry` a member, `pref_service_` will keep a
  // reference to it.
  auto pref_registry = base::MakeRefCounted<PrefRegistrySimple>();
  metrics::MetricsService::RegisterPrefs(pref_registry.get());
  // This is the pref used to globally enable/disable metrics reporting. When
  // metrics reporting is toggled via any method (e.g., command line, JS API
  // call, etc., this is the setting that's overridden).
  pref_registry->RegisterBooleanPref(metrics::prefs::kMetricsReportingEnabled,
                                     false);
  pref_registry->RegisterStringPref(kUserAgentOverridePrefName,
                                     std::string());
  base::FilePath path =
      GetPrefFilePath(kMetricsConfigFilename, "metrics config");

  PrefServiceFactory pref_service_factory;
  // TODO(b/397929564): Investigate using a Chrome's memory-mapped file store
  // instead of in-memory.
  pref_service_factory.set_user_prefs(
      base::MakeRefCounted<JsonPrefStore>(path));

  metrics_local_state_ = pref_service_factory.Create(std::move(pref_registry));
}

void GlobalFeatures::RestorePersistedSettings() {
  if (!metrics_local_state_) {
    return;
  }

  const std::string persisted_user_agent_override =
      metrics_local_state_->GetString(kUserAgentOverridePrefName);

  base::AutoLock auto_lock(lock_);
  if (persisted_user_agent_override.empty()) {
    settings_.erase(kUserAgentOverridePrefName);
  } else {
    settings_[kUserAgentOverridePrefName] = persisted_user_agent_override;
  }
}

void GlobalFeatures::InitializeActiveConfigData() {
  DCHECK(experiment_config_);
  DCHECK(experiment_config_manager_);
  auto experiment_config_type =
      experiment_config_manager_->GetExperimentConfigType();
  if (experiment_config_type == ExperimentConfigType::kEmptyConfig) {
    return;
  }

  active_config_data_ = experiment_config_->GetString(
      (experiment_config_type == ExperimentConfigType::kSafeConfig)
          ? kSafeConfigActiveConfigData
          : kExperimentConfigActiveConfigData);
}

base::FilePath GlobalFeatures::GetPrefFilePath(
    const base::FilePath::CharType filename[],
    const char* label) {
  base::FilePath path;
  CHECK(base::PathService::Get(base::DIR_CACHE, &path));
  path = path.Append(filename);

  CHECK(base::CreateDirectory(path.DirName()))
      << "Failed to create directory for " << label << ": "
      << path.DirName().value();
  return path;
}

void GlobalFeatures::Shutdown() {
  metrics::MetricsService* metrics = metrics_service();
  if (metrics) {
    metrics->LogCleanShutdown();
  }
}

// static
void GlobalFeatures::RegisterPrefs(PrefRegistrySimple* registry) {
  registry->RegisterDictionaryPref(kExperimentConfig);
  registry->RegisterStringPref(kExperimentConfigActiveConfigData,
                               std::string());
  registry->RegisterDictionaryPref(kExperimentConfigFeatures);
  registry->RegisterDictionaryPref(kExperimentConfigFeatureParams);
  registry->RegisterStringPref(kExperimentConfigMinVersion, std::string());
  registry->RegisterDictionaryPref(kFinchParameters);
  registry->RegisterStringPref(kLatestConfigHash, std::string());
  registry->RegisterDictionaryPref(kSafeConfig);
  registry->RegisterStringPref(kSafeConfigActiveConfigData, std::string());
  registry->RegisterDictionaryPref(kSafeConfigFeatures);
  registry->RegisterDictionaryPref(kSafeConfigFeatureParams);
  registry->RegisterStringPref(kSafeConfigMinVersion, std::string());
  registry->RegisterTimePref(variations::prefs::kVariationsLastFetchTime,
                             base::Time(), PrefRegistry::LOSSY_PREF);
}

}  // namespace cobalt
