// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

export type AuthSetupMessageFromIde = {
    stage: Stage,
    regions: Region[],
    idcInfo: IdcInfo,
    cancellable: boolean,
    feature: string,
    existConnections: AwsBearerTokenConnection[],
}

export type ListProfilesMessageFromIde = {
    stage: Stage,
    status: 'succeeded' | 'failed' | 'pending',
    profiles: Profile[],
    errorMessage: string
}


// plugin interface [AwsBearerTokenConnection]
export interface AwsBearerTokenConnection {
    sessionName: string,
    startUrl: string,
    region: string,
    scopes: string[],
    id: string
}

export const SONO_URL = "https://view.awsapps.com/start"

export type Stage =
    'START' |
    'SSO_FORM' |
    'CONNECTED' |
    'AUTHENTICATING' |
    'AWS_PROFILE' |
    'REAUTH' |
    'PROFILE_SELECT'

export type Feature = 'Q' | 'codecatalyst' | 'awsExplorer'

export interface Region {
    id: string,
    name: string,
    partitionId: string,
    category: string,
    displayName: string
}

export interface IdcInfo {
    startUrl: string,
    region: string,
}

export interface State {
    stage: Stage,
    ssoRegions: Region[],
    authorizationCode: string | undefined,
    lastLoginIdcInfo: IdcInfo,
    feature: Feature,
    cancellable: boolean,
    existingConnections: AwsBearerTokenConnection[],
    listProfilesResult: ListProfileResult | undefined,
    selectedProfile: Profile | undefined
}

export interface ListProfileResult {
    status: 'succeeded' | 'failed' | 'pending'
}

export class ListProfileSuccessResult implements ListProfileResult {
    status: 'succeeded' = 'succeeded'
    constructor(readonly profiles: Profile[]) {}
}

export class ListProfileFailureResult implements ListProfileResult {
    status: 'failed' = 'failed'
    constructor(readonly errorMessage: string) {}
}

export class ListProfilePendingResult implements ListProfileResult {
    status: 'pending' = 'pending'
    constructor() {}
}

export enum LoginIdentifier {
    NONE = 'none',
    BUILDER_ID = 'builderId',
    ENTERPRISE_SSO = 'idc',
    IAM_CREDENTIAL = 'iam',
    EXISTING_LOGINS = 'existing',
}

export interface LoginOption {
    id: LoginIdentifier

    requiresBrowser(): boolean
}

export interface Profile {
    profileName: string
    accountId: string
    region: string
    arn: String
}

export const GENERIC_PROFILE_LOAD_ERROR = "We couldn't load your Q Developer profiles. Please try again.";

export class LongLivedIAM implements LoginOption {
    id: LoginIdentifier = LoginIdentifier.IAM_CREDENTIAL

    constructor(readonly profileName: string, readonly accessKey: string, readonly secret: string) {
    }

    requiresBrowser(): boolean {
        return false
    }
}

export class IdC implements LoginOption {
    id: LoginIdentifier = LoginIdentifier.ENTERPRISE_SSO

    constructor(readonly url: string, readonly region: string) {
    }

    requiresBrowser(): boolean {
        return true
    }
}

export class BuilderId implements LoginOption {
    id: LoginIdentifier = LoginIdentifier.BUILDER_ID

    requiresBrowser(): boolean {
        return true
    }
}

export class ExistConnection implements LoginOption {
    id: LoginIdentifier = LoginIdentifier.EXISTING_LOGINS

    constructor(readonly pluginConnectionId: string) {}

    // this case only happens for bearer connection for now
    requiresBrowser(): boolean {
        return true
    }
}
