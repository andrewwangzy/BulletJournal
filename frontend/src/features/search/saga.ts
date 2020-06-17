import {all, call, put, select, takeLatest} from 'redux-saga/effects';
import {message} from 'antd';
import {IState} from '../../store';
import {fetchSearchResults} from '../../apis/queryApis';
import {PayloadAction} from "redux-starter-kit";
import {actions as searchActions, SearchAction} from "./reducer";
import {SearchResult, searchResultPageSize} from "./interface";

function* search(action: PayloadAction<SearchAction>) {
    try {
        const {term, scrollId} = action.payload;
        if (term.length < 3) {
            yield call(message.error, 'Please enter at least 3 characters to search');
            return;
        }
        const state: IState = yield select();
        let data: SearchResult = yield call(fetchSearchResults,
            term, state.search.searchPageNo, searchResultPageSize, scrollId);
        if (scrollId) {
            const oldList = state.search.searchResult!.searchResultItemList;
            const result =
                data.searchResultItemList.concat(oldList);
            data.searchResultItemList = result;
            yield put(searchActions.updateLoadingMore({loadingMore: true}));
        } else {
            yield put(searchActions.updateSearching({searching: true}));
        }
        yield put(searchActions.searchResultReceived({searchResult: data}));
        yield put(searchActions.updateSearching({searching: false}));
        yield put(searchActions.updateLoadingMore({loadingMore: false}));
        yield put(searchActions.updateSearchPageNo({
            searchPageNo: state.search.searchPageNo + 1
        }));
    } catch (error) {
        yield call(message.error, `search Error Received: ${error}`);
    }
    yield put(searchActions.updateSearching({searching: false}));
    yield put(searchActions.updateLoadingMore({loadingMore: false}));
}

export default function* searchSagas() {
    yield all([
        yield takeLatest(searchActions.search.type, search),
    ]);
}