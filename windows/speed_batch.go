package main

import (
	"context"
	"errors"
	"fmt"
	"time"
)

type SpeedBatch struct {
	Done     int    `json:"done"`
	Total    int    `json:"total"`
	Failed   int    `json:"failed"`
	Skipped  int    `json:"skipped"`
	Bytes    int64  `json:"bytes"`
	Current  string `json:"current"`
	Filter   Filter `json:"filter"`
	Started  int64  `json:"started"`
	Canceled bool   `json:"canceled"`
}
type speedProbe func(context.Context, Physical, Endpoint, func(float64, int64)) (float64, int64, error)

var errTestBusy = errors.New("ابتدا تست جاری را تمام یا متوقف کن")
var errMissingProxy = errors.New("پروکسی در فهرست سالم‌ها نیست")

// One batch worker avoids distorting bandwidth measurements and leaves a slot for single tests.
func (a *App) startFilteredSpeed() error {
	a.mu.Lock()
	if a.speedBatchCancel != nil {
		a.mu.Unlock()
		return errors.New("تست گروهی در حال اجراست")
	}
	if !physicalReady(a.physical) {
		a.mu.Unlock()
		return errors.New("شبکهٔ اصلی آماده نیست")
	}
	rows := filteredRows(a.disk.Results, a.disk.Filter, a.disk.Settings.TLSOnly)
	if len(rows) == 0 {
		a.mu.Unlock()
		return errors.New("نتیجه‌ای مطابق فیلتر وجود ندارد")
	}
	ctx, cancel := context.WithCancel(context.Background())
	a.speedBatchCancel = cancel
	a.speedBatchDone = 0
	a.speedBatchTotal = len(rows)
	a.speedBatch = SpeedBatch{Total: len(rows), Filter: a.disk.Filter, Started: time.Now().UnixMilli()}
	a.recordLocked(fmt.Sprintf("شروع تست سرعت %d نتیجهٔ فیلترشده در همهٔ صفحات", len(rows)))
	a.mu.Unlock()
	go func() {
		defer func() {
			cancel()
			a.mu.Lock()
			a.speedBatch.Current = ""
			a.speedBatch.Canceled = ctx.Err() != nil && a.speedBatch.Done < a.speedBatch.Total
			a.recordLocked(fmt.Sprintf("تست سرعت: %d از %d • ناموفق %d • حجم %d بایت", a.speedBatch.Done, a.speedBatch.Total, a.speedBatch.Failed, a.speedBatch.Bytes))
			a.mu.Unlock()
			a.saveChecked()
			a.mu.Lock()
			a.speedBatchCancel = nil
			a.mu.Unlock()
		}()
		for _, e := range rows {
			if ctx.Err() != nil {
				return
			}
			key := e.key()
			var task *TestTask
			var err error
			for {
				task, err = a.runSingleTask(key, "speed", ctx, true)
				if err != errTestBusy {
					break
				}
				select {
				case <-ctx.Done():
					return
				case <-time.After(50 * time.Millisecond):
				}
			}
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				a.mu.Lock()
				a.speedBatch.Skipped++
				a.speedBatch.Done++
				a.speedBatchDone = a.speedBatch.Done
				a.mu.Unlock()
				continue
			}
			a.mu.Lock()
			a.speedBatch.Current = key
			a.mu.Unlock()
			select {
			case <-task.finished:
			case <-ctx.Done():
				<-task.finished
				return
			}
			if ctx.Err() != nil {
				return
			}
			a.mu.Lock()
			if a.disk.Tests[key].SpeedError != "" {
				a.speedBatch.Failed++
			}
			a.speedBatch.Done++
			a.speedBatchDone = a.speedBatch.Done
			a.mu.Unlock()
		}
	}()
	return nil
}
func (a *App) stopFilteredSpeed() {
	a.mu.Lock()
	if a.speedBatchCancel != nil {
		a.speedBatchCancel()
	}
	a.mu.Unlock()
}
func (a *App) stopOperations() {
	a.mu.Lock()
	a.autoConnect = false
	if a.fetchCancel != nil {
		a.fetchCancel()
	}
	if a.scanCancel != nil {
		a.scanCancel()
	}
	if a.speedBatchCancel != nil {
		a.speedBatchCancel()
	}
	for _, t := range a.tasks {
		t.cancel()
	}
	if a.vpnState == "connecting" && a.connectCancel != nil {
		a.userDisconnect = true
		a.controlGeneration++
		a.connectCancel()
	}
	a.recordLocked("توقف عملیات جاری")
	a.mu.Unlock()
}
